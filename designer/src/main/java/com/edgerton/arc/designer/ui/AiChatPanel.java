package com.edgerton.arc.designer.ui;

import com.jidesoft.docking.DockContext;
import com.jidesoft.docking.DockableFrame;
import java.awt.Dimension;

public class AiChatPanel extends DockableFrame {

  public AiChatPanel(ChatWindow chatWindow) {
    super("claude");
    setTitle("Claude");
    setInitSide(DockContext.DOCK_SIDE_EAST);
    setInitMode(DockContext.STATE_FRAMEDOCKED);
    setPreferredSize(new Dimension(380, 600));
    getContentPane().add(chatWindow);
  }
}
