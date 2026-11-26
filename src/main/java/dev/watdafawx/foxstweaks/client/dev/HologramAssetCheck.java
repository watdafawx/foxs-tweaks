package dev.watdafawx.foxstweaks.client.dev;

import dev.watdafawx.foxstweaks.client.asset.HologramAssets;
import dev.watdafawx.foxstweaks.hologram.asset.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/** Real client commands -> optional payload -> store -> download -> DynamicTexture; no optional mods needed. */
public final class HologramAssetCheck {
    private static final List<String> REPORT=new ArrayList<>();
    private static boolean requested,finished;private static int tick,stage,failures;
    private static String image="",gif="",worldName="";private static boolean reopening,disconnecting;private static double playback;
    private static final String[] FILES={"test.png","oversize.png","fake.png","bomb.png","test.gif"};
    private static Path run() {return Minecraft.getInstance().gameDirectory.toPath();}
    public static void install() {NeoForge.EVENT_BUS.addListener(HologramAssetCheck::tick);}
    private static void check(String name,boolean ok) {REPORT.add((ok?"PASS ":"FAIL ")+name);if(!ok)failures++;}
    private static <T>T server(Function<ServerPlayer,T> fn) {var mc=Minecraft.getInstance();return mc.getSingleplayerServer().submit(()->fn.apply(mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID()))).join();}
    private static void upload(String path) throws Exception {
        var mc=Minecraft.getInstance();
        // Same dispatcher entry used when user submits /foxstweaksc asset upload in chat.
        net.neoforged.neoforge.client.ClientCommandHandler.getDispatcher().execute("foxstweaksc asset upload "+path,net.neoforged.neoforge.client.ClientCommandHandler.getSource());
    }
    private static void fixtures() throws Exception {
        Path dir=run().resolve("foxstweaks/upload");Files.createDirectories(dir);
        ImageIO.write(picture(64,64,0xffff0000),"PNG",dir.resolve("test.png").toFile());
        var oversized=picture(512,128,0xff00ff00);var random=new Random(1);
        for(int y=0;y<128;y++)for(int x=0;x<512;x++)oversized.setRGB(x,y,0xff000000|random.nextInt(0x1000000));
        ImageIO.write(oversized,"PNG",dir.resolve("oversize.png").toFile());
        Files.writeString(dir.resolve("fake.png"),"not a png image");
        byte[] bomb=Files.readAllBytes(dir.resolve("test.png"));
        java.nio.ByteBuffer.wrap(bomb).putInt(16,100000).putInt(20,100000);
        java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(bomb,12,17);java.nio.ByteBuffer.wrap(bomb).putInt(29,(int)crc.getValue());
        Files.write(dir.resolve("bomb.png"),bomb);
        var writer=ImageIO.getImageWritersByFormatName("gif").next();
        try(var out=ImageIO.createImageOutputStream(dir.resolve("test.gif").toFile())) {
            writer.setOutput(out);writer.prepareWriteSequence(null);
            for(int color:new int[]{0xffff0000,0xff0000ff}) {
                var pic=picture(64,64,color);var meta=writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(pic),null);
                var root=(IIOMetadataNode)meta.getAsTree("javax_imageio_gif_image_1.0");
                var control=(IIOMetadataNode)root.getElementsByTagName("GraphicControlExtension").item(0);control.setAttribute("delayTime","50");
                meta.setFromTree("javax_imageio_gif_image_1.0",root);writer.writeToSequence(new IIOImage(pic,null,meta),null);
            }writer.endWriteSequence();
        }finally {writer.dispose();}
    }
    private static BufferedImage picture(int w,int h,int color) {var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();g.setColor(new java.awt.Color(color,true));g.fillRect(0,0,w,h);g.dispose();return image;}
    private static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();mc.options.pauseOnLostFocus=false;mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
        if(!requested && mc.getOverlay()==null && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
            requested=true;String world="asset-check-"+System.currentTimeMillis();worldName=world;
            mc.createWorldOpenFlows().createFreshLevel(world,new LevelSettings(world,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),new WorldOptions(0L,false,false),access->access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),new TitleScreen());
        }
        if(disconnecting)return;
        if(reopening && mc.player==null && mc.getSingleplayerServer()==null && mc.screen instanceof TitleScreen) {
            reopening=false;mc.createWorldOpenFlows().openWorld(worldName,()->{});
        }
        if(mc.player==null || finished)return;
        if(!gif.isEmpty())HologramAssets.texture(gif,playback);
        if(++tick%70!=0)return;
        try {
            if(stage==0) {fixtures();upload(FILES[0]);}
            else if(stage==1) {check("PNG accepted via client command",HologramAssets.lastSuccess && AssetFrames.validId(HologramAssets.lastResult));image=HologramAssets.lastResult;upload(FILES[1]);}
            else if(stage==2) {
                check("oversize accepted",HologramAssets.lastSuccess);
                check("aspect preserving 256x64 downscale",server(p->{try {var m=AssetNetwork.store(p.server).info(HologramAssets.lastResult);return m!=null&&m.width()==256&&m.height()==64;}catch(IOException e){return false;}}));upload(FILES[2]);
            } else if(stage==3) {check("fake magic refused with reason",!HologramAssets.lastSuccess && HologramAssets.lastResult.contains("magic"));upload(FILES[3]);}
            else if(stage==4) {check("bomb header refused before decode",!HologramAssets.lastSuccess && HologramAssets.lastResult.contains("bomb"));upload(FILES[4]);}
            else if(stage==5) {
                check("GIF accepted",HologramAssets.lastSuccess);gif=HologramAssets.lastResult;
                HologramAssets.clearMemory();Files.deleteIfExists(HologramAssets.cachePath().resolve(gif));HologramAssets.texture(gif,0);
            } else if(stage==6) {
                check("fresh cache downloaded and hash verified",Files.exists(HologramAssets.cachePath().resolve(gif))&&AssetFrames.hash(Files.readAllBytes(HologramAssets.cachePath().resolve(gif))).equals(gif));
                check("texture ready",HologramAssets.texture(gif,0)!=null);
                AssetFrames f=AssetFrames.bundle(Files.readAllBytes(HologramAssets.cachePath().resolve(gif)));
                check("GIF frames differ",f.frame(0)!=f.frame(0.6)&&!Arrays.equals(f.frames().get(0),f.frames().get(1)));
                mc.setScreen(new Overlay());playback=0;
            } else if(stage==7) {shot("asset-frame-red.png");playback=0.6;}
            else if(stage==8) {shot("asset-frame-blue.png");}
            else if(stage==9) {
                var red = ImageIO.read(run().resolve("screenshots/asset-frame-red.png").toFile());
                var blue = ImageIO.read(run().resolve("screenshots/asset-frame-blue.png").toFile());
                check("rendered GIF screenshots change red to blue", (red.getRGB(red.getWidth()/2,red.getHeight()/2)&0xffffff)==0xff0000
                        && (blue.getRGB(blue.getWidth()/2,blue.getHeight()/2)&0xffffff)==0x0000ff);
                check("world quota and player cap enforced; index reload persists",server(p->{try {
                    var store=AssetNetwork.store(p.server);byte[] bytes=Files.readAllBytes(run().resolve("foxstweaks/upload/test.png"));
                    // New content to avoid deduplication masking a quota failure.
                    var out=new ByteArrayOutputStream();ImageIO.write(picture(33,33,0xffaa00aa),"PNG",out);bytes=out.toByteArray();
                    boolean quota=false,cap=false;
                    try {store.put(p.getUUID(),"quota",bytes,new AssetLimits(2097152,1,256,15,10,100));}catch(IOException e){quota=e.getMessage().contains("quota");}
                    try {store.put(p.getUUID(),"cap",bytes,new AssetLimits(2097152,209715200,256,15,10,1));}catch(IOException e){cap=e.getMessage().contains("cap");}
                    AssetStore reloaded=new AssetStore(AssetStore.path(p.server));return quota&&cap&&reloaded.info(gif)!=null&&reloaded.info(image)!=null;
                }catch(IOException e){return false;}}));
                try {HologramAssets.upload("../escape.png");check("path traversal refused",false);}catch(IOException e){check("path traversal refused",true);}
                server(p->{p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4),"foxstweaks asset list");p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4),"foxstweaks asset delete "+image);return null;});
            } else if(stage==10) {
                AssetLimits normal=new AssetLimits(2097152,209715200,256,15,10,100);
                var jpeg=new ByteArrayOutputStream();
                var rgb=new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB);
                var graphics=rgb.createGraphics();graphics.setColor(java.awt.Color.GREEN);graphics.fillRect(0,0,64,64);graphics.dispose();
                ImageIO.write(rgb,"JPEG",jpeg);
                byte[] sequence=new AssetFrames(List.of(jpeg.toByteArray(),jpeg.toByteArray()),List.of(1,1),64,64).encode();
                var canonical=AssetFrames.sanitize(sequence,normal);
                check("JPEG frame sequence canonicalized to PNG and FPS capped", canonical.frames().size()==2 && canonical.frames().getFirst()[0]==(byte)137 && canonical.delays().getFirst()==67);
                byte[] longAnimation=new AssetFrames(List.of(jpeg.toByteArray(),jpeg.toByteArray()),List.of(6000,6000),64,64).encode();
                try {AssetFrames.sanitize(longAnimation,normal);check("duration limit refused",false);}catch(IOException e){check("duration limit refused",e.getMessage().contains("duration"));}
                server(p->{try {var store=AssetNetwork.store(p.server);store.retain(gif);store.release(gif);check("retain/release preserves unreferenced asset",store.info(gif)!=null&&store.info(gif).references()==0);}catch(IOException e){check("retain/release",false);}return null;});
                check("operator delete command removes asset",server(p->{try{return AssetNetwork.store(p.server).info(image)==null;}catch(IOException e){return false;}}));}
            else if(stage==11) {stage++;server(p->{if(p.server.getWorldData() instanceof net.minecraft.world.level.storage.PrimaryLevelData data)data.withConfirmedWarning(true);return null;});disconnecting=true;mc.level.disconnect();mc.disconnect(new TitleScreen());disconnecting=false;reopening=true;return;}
            else if(stage==12) {
                check("full world reload preserves asset index and blobs",server(p->{try {var store=AssetNetwork.store(p.server);return store.info(gif)!=null&&AssetFrames.hash(store.read(gif)).equals(gif)&&store.info(image)==null;}catch(IOException e){return false;}}));finish();
            }
            stage++;
        } catch(Throwable e) {REPORT.add("FAIL exception "+e);failures++;finish();}
    }
    private static final class Overlay extends Screen {
        Overlay(){super(net.minecraft.network.chat.Component.literal("Asset animation check"));}
        @Override public void render(net.minecraft.client.gui.GuiGraphics g,int mx,int my,float partial) {
            g.fill(0,0,width,height,0xff111111);var tex=HologramAssets.texture(gif,playback);
            if(tex!=null)g.blit(tex,width/2-64,height/2-64,0,0,128,128,128,128);
            g.drawString(font,"GIF frame "+(playback==0?"red":"blue"),width/2-64,height/2+72,0xffffffff);
        }
        @Override public boolean isPauseScreen(){return false;}
    }
    private static void shot(String name) {var mc=Minecraft.getInstance();Screenshot.grab(mc.gameDirectory,name,mc.getMainRenderTarget(),m->{});}
    private static void finish() {finished=true;REPORT.add("done: "+(failures==0?"ALL PASS":failures+" FAILURE(S)"));try{Files.write(run().resolve("foxstweaks-hologram-asset-check.txt"),REPORT);}catch(IOException e){throw new java.io.UncheckedIOException(e);}Minecraft.getInstance().stop();}
}
