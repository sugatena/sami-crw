package crw.ui;

import java.awt.BorderLayout;
import java.util.ArrayList;
import sami.uilanguage.UiFrame;
import crw.ui.widget.SensorDataWidget;
import crw.ui.widget.RobotTrackWidget;
import crw.ui.widget.RobotWidget;
import crw.ui.widget.RobotWidget.ControlMode;
import crw.ui.component.WorldWindPanel;
import crw.ui.widget.SelectGeometryWidget;
import java.util.Arrays;
import java.util.List;

/**
 *
 * @author nbb
 */
public class MapFrame extends UiFrame {

    public WorldWindPanel wwPanel;

    public MapFrame() {
        super("MapFrame");
        getContentPane().setLayout(new BorderLayout());

        // Add map
        wwPanel = new WorldWindPanel();
        wwPanel.createMap();
        List<SelectGeometryWidget.SelectMode> modes = Arrays.asList(SelectGeometryWidget.SelectMode.POINT, SelectGeometryWidget.SelectMode.PATH, SelectGeometryWidget.SelectMode.AREA, SelectGeometryWidget.SelectMode.NONE, SelectGeometryWidget.SelectMode.CLEAR);
        SelectGeometryWidget select = new SelectGeometryWidget(wwPanel, modes, SelectGeometryWidget.SelectMode.NONE);
        wwPanel.addWidget(select);
        getContentPane().add(wwPanel.component, BorderLayout.CENTER);
        // Add widgets
        SensorDataWidget data = new SensorDataWidget(wwPanel);
        wwPanel.addWidget(data);
        ArrayList<ControlMode> controlModes = new ArrayList<ControlMode>();
        controlModes.add(ControlMode.TELEOP);
        controlModes.add(ControlMode.POINT);
        controlModes.add(ControlMode.PATH);
        controlModes.add(ControlMode.NONE);
        RobotWidget robot = new RobotWidget(wwPanel, controlModes);
        wwPanel.addWidget(robot);
        RobotTrackWidget robotTrack = new RobotTrackWidget(wwPanel);
        wwPanel.addWidget(robotTrack);

        pack();
        setVisible(true);
    }

    public static void main(String[] args) {
        MapFrame mf = new MapFrame();
    }
}
