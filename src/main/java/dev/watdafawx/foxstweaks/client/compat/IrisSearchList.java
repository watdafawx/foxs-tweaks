package dev.watdafawx.foxstweaks.client.compat;

import net.irisshaders.iris.gui.element.ShaderPackOptionList;

/** Added to Iris' option list by IrisOptionListSearchMixin: lets the search add its own rows. */
public interface IrisSearchList {
    void foxstweaks$addRow(ShaderPackOptionList.BaseEntry entry);
}
