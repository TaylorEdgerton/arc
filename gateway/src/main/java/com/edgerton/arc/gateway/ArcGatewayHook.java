package com.edgerton.arc.gateway;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

public class ArcGatewayHook extends AbstractGatewayModuleHook {

  @Override
  public void setup(GatewayContext context) {}

  @Override
  public void startup(LicenseState activationState) {}

  @Override
  public void shutdown() {}

  @Override
  public boolean isFreeModule() {
    return true;
  }

  @Override
  public boolean isMakerEditionCompatible() {
    return true;
  }
}
