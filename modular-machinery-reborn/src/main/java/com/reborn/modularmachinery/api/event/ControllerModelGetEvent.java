package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.machine.GeoModelSpec;
import javax.annotation.Nullable;

/** Client model selection, independent of GeckoLib and KubeJS classes. */
public final class ControllerModelGetEvent extends MachineEvent {
    @Nullable private final GeoModelSpec defaultModel;
    private String modelName = "";
    @Nullable private GeoModelSpec model;
    public ControllerModelGetEvent() { defaultModel = null; }
    public ControllerModelGetEvent(MachineControllerBlockEntity controller, @Nullable GeoModelSpec defaultModel) {
        super(controller, controller.machineId()); this.defaultModel = defaultModel;
    }
    @Nullable public GeoModelSpec getDefaultModel() { return defaultModel; }
    public String getModelName() { return modelName; }
    public void setModelName(String name) { modelName = name == null ? "" : name; model = null; }
    @Nullable public GeoModelSpec getModel() { return model; }
    public void setModel(GeoModelSpec model) { this.model = model; modelName = ""; }
    public void reset() { modelName = ""; model = null; }
}
