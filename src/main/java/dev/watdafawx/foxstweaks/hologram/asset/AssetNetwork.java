package dev.watdafawx.foxstweaks.hologram.asset;

import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.commands.Commands;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.io.*;
import java.util.*;

@EventBusSubscriber(modid="foxstweaks")
public final class AssetNetwork {
    private static AssetStore store;
    private static MinecraftServer server;
    private static final Map<UUID,Upload> UPLOADS=new HashMap<>();
    private static final Map<UUID,Budget> BUDGETS=new HashMap<>();
    private static final Map<UUID,Download> DOWNLOADS=new HashMap<>();
    private static final long TIMEOUT=30000;
    private static final class Upload {
        final String name; final int total; final ByteArrayOutputStream data=new ByteArrayOutputStream();
        int sequence; long touched=System.currentTimeMillis();
        Upload(String name,int total) {this.name=name;this.total=total;}
    }
    private record Download(String id,byte[] bytes,long touched) {}
    private static final class Budget {long second;int packets;long begin;}
    public static AssetStore store(MinecraftServer s) throws IOException {
        if(server!=s || store==null) {store=new AssetStore(AssetStore.path(s));server=s;}return store;
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playBidirectional(AssetPayload.TYPE,AssetPayload.CODEC,(p,c)->c.enqueueWork(()-> {
            if(c.player() instanceof ServerPlayer sp) handle(sp,p);
            else if(FMLEnvironment.dist.isClient()) dev.watdafawx.foxstweaks.client.asset.HologramAssets.accept(p);
        }));
    }
    private static void reply(ServerPlayer p, boolean ok,String message,int operation) {
        if(p.connection.hasChannel(AssetPayload.TYPE)) PacketDistributor.sendToPlayer(p,AssetPayload.request(AssetPayload.RESULT,ok?1:0,operation,message.length()>256?message.substring(0,256):message));
    }
    public static void handle(ServerPlayer player,AssetPayload p) {
        if(!player.connection.hasChannel(AssetPayload.TYPE))return;
        long now=System.currentTimeMillis();UUID id=player.getUUID();
        Budget budget=BUDGETS.computeIfAbsent(id,k->new Budget());
        if(now/1000!=budget.second) {budget.second=now/1000;budget.packets=0;}
        if(++budget.packets>40) {UPLOADS.remove(id);return;}
        try {
            if(p.kind()==AssetPayload.ABORT) {UPLOADS.remove(id);DOWNLOADS.remove(id);return;}
            if(p.kind()==AssetPayload.GET) {
                if(!AssetFrames.validId(p.text()) || p.number()<0 || p.number()%AssetPayload.CHUNK_BYTES!=0) throw new IOException("Invalid asset request");
                Download d=DOWNLOADS.get(id);
                if(d==null || !d.id.equals(p.text())) {
                    if(p.number()!=0)throw new IOException("Download must begin at zero");
                    d=new Download(p.text(),store(player.server).read(p.text()),now);DOWNLOADS.put(id,d);
                }
                if(p.number()>=d.bytes.length)throw new IOException("Invalid download offset");
                int end=Math.min(d.bytes.length,p.number()+AssetPayload.CHUNK_BYTES);
                PacketDistributor.sendToPlayer(player,new AssetPayload(AssetPayload.DATA,p.number(),d.bytes.length,d.id,Arrays.copyOfRange(d.bytes,p.number(),end)));
                if(end==d.bytes.length)DOWNLOADS.remove(id);else DOWNLOADS.put(id,new Download(d.id,d.bytes,now));return;
            }
            if(!ServerConfig.ASSET_UPLOAD_EVERYONE.get() && !player.hasPermissions(2))throw new IOException("Asset uploads require operator permission");
            if(p.kind()==AssetPayload.BEGIN) {
                if(now-budget.begin<1000)throw new IOException("Upload rate limit: wait one second");budget.begin=now;
                if(UPLOADS.containsKey(id))throw new IOException("An upload is already in progress");
                if(p.total()<8 || p.total()>AssetLimits.current().fileBytes())throw new IOException("File byte limit exceeded");
                UPLOADS.put(id,new Upload(p.text(),p.total()));return;
            }
            if(p.kind()!=AssetPayload.CHUNK)throw new IOException("Invalid asset operation");
            Upload u=UPLOADS.get(id);
            if(u==null || now-u.touched>TIMEOUT)throw new IOException("Upload expired or not started");
            if(p.number()!=u.sequence++ || p.bytes().length==0 || p.bytes().length>AssetPayload.CHUNK_BYTES || u.data.size()+p.bytes().length>u.total)throw new IOException("Invalid upload sequence or size");
            u.touched=now;u.data.write(p.bytes());
            if(u.data.size()==u.total) {
                UPLOADS.remove(id);
                var meta=store(player.server).put(id,u.name,u.data.toByteArray(),AssetLimits.current());
                reply(player,true,meta.id(),p.kind());
            }
        } catch(IOException|RuntimeException e) {UPLOADS.remove(id);reply(player,false,Objects.toString(e.getMessage(),"Asset operation failed"),p.kind());}
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post e) {
        long now=System.currentTimeMillis();UPLOADS.values().removeIf(u->now-u.touched>TIMEOUT);DOWNLOADS.values().removeIf(d->now-d.touched>TIMEOUT);
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {var id=e.getEntity().getUUID();UPLOADS.remove(id);DOWNLOADS.remove(id);BUDGETS.remove(id);}
    @SubscribeEvent public static void stop(ServerStoppedEvent e) {UPLOADS.clear();DOWNLOADS.clear();BUDGETS.clear();store=null;server=null;}
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("foxstweaks").then(Commands.literal("asset")
            .then(Commands.literal("list").executes(c->command(c.getSource(),"list","")))
            .then(Commands.literal("info").then(Commands.argument("id",StringArgumentType.word()).executes(c->command(c.getSource(),"info",StringArgumentType.getString(c,"id")))))
            .then(Commands.literal("delete").requires(s->s.hasPermission(2)).then(Commands.argument("id",StringArgumentType.word()).executes(c->command(c.getSource(),"delete",StringArgumentType.getString(c,"id")))))));
    }
    private static int command(net.minecraft.commands.CommandSourceStack source,String action,String id) {
        try {
            var s=store(source.getServer());
            if(action.equals("list")) {
                source.sendSuccess(()->Component.literal("Assets: "+s.list().size()+"; world "+s.bytes()+"/"+AssetLimits.current().worldBytes()+" bytes"),false);
                if(source.getEntity() instanceof ServerPlayer p)source.sendSuccess(()->Component.literal("Your assets: "+s.count(p.getUUID())+"/"+AssetLimits.current().perPlayer()),false);
                for(var m:s.list())source.sendSuccess(()->Component.literal(m.id()+" "+m.name()+" "+m.byteSize()+" bytes"),false);
            } else if(action.equals("info")) {
                var m=s.info(id);if(m==null)throw new IOException("Unknown asset");source.sendSuccess(()->Component.literal(m.toString()),false);
            } else {if(!s.delete(id))throw new IOException("Unknown asset");source.sendSuccess(()->Component.literal("Deleted "+id),true);}
            return 1;
        } catch(IOException e) {source.sendFailure(Component.literal(e.getMessage()));return 0;}
    }
}
