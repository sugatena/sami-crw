package crw.ui.teleop;

import com.platypus.crw.AsyncVehicleServer;
import com.platypus.crw.FunctionObserver;
import crw.proxy.BoatProxy;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.DecimalFormat;
import java.util.logging.Logger;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import sami.engine.Engine;
import sami.sensor.Observation;
import sami.sensor.ObservationListenerInt;
import sami.sensor.ObserverInt;

/**
 *
 * @author nbb
 */
public class GainsPanel extends JScrollPane implements ObservationListenerInt {

    private static final Logger LOGGER = Logger.getLogger(GainsPanel.class.getName());
    public static final int THRUST_GAINS_AXIS = 0;
    public static final int RUDDER_GAINS_AXIS = 5;
    public static final int WINCH_GAINS_AXIS = 3;
    public static final boolean USE_VEL_MULTIPLIER = false;
    private JPanel contentP, velMultP, thrustPidP, rudderPidP, winchPidP;
    public JTextField velocityMultF, winchTF, thrustPTF, thrustITF, thrustDTF, rudderPTF, rudderITF, rudderDTF;
    public JLabel winchL;
    private DecimalFormat decimalFormat = new DecimalFormat("#.##");
    public JButton applyB;
    public double winch, thrustP, thrustI, thrustD, rudderP, rudderI, rudderD;
    private BoatProxy activeProxy = null;
    private AsyncVehicleServer activeVehicle = null;
    private ObserverInt activeWinchObserver = null;

    public GainsPanel() {
        super();
        velocityMultF = new JTextField("1.0");
        velocityMultF.setPreferredSize(new Dimension(50, velocityMultF.getPreferredSize().height));
        winchTF = new JTextField("");
        winchTF.setPreferredSize(new Dimension(50, winchTF.getPreferredSize().height));
        thrustPTF = new JTextField("");
        thrustPTF.setPreferredSize(new Dimension(50, thrustPTF.getPreferredSize().height));
        thrustITF = new JTextField("");
        thrustITF.setPreferredSize(new Dimension(50, thrustITF.getPreferredSize().height));
        thrustDTF = new JTextField("");
        thrustDTF.setPreferredSize(new Dimension(50, thrustDTF.getPreferredSize().height));
        rudderPTF = new JTextField("");
        rudderPTF.setPreferredSize(new Dimension(50, rudderPTF.getPreferredSize().height));
        rudderITF = new JTextField("");
        rudderITF.setPreferredSize(new Dimension(50, rudderITF.getPreferredSize().height));
        rudderDTF = new JTextField("");
        rudderDTF.setPreferredSize(new Dimension(50, rudderDTF.getPreferredSize().height));

        if (USE_VEL_MULTIPLIER) {
            velMultP = new JPanel();
            velMultP.setBorder(BorderFactory.createLineBorder(Color.BLACK));
            velMultP.add(new JLabel("Velocity multiplier:"));
            velMultP.add(velocityMultF);
        }

        thrustPidP = new JPanel();
        thrustPidP.setBorder(BorderFactory.createLineBorder(Color.BLACK));
        thrustPidP.add(new JLabel("Thrust "));
        thrustPidP.add(new JLabel("P:"));
        thrustPidP.add(thrustPTF);
        thrustPidP.add(new JLabel("I:"));
        thrustPidP.add(thrustITF);
        thrustPidP.add(new JLabel("D:"));
        thrustPidP.add(thrustDTF);

        rudderPidP = new JPanel();
        rudderPidP.setBorder(BorderFactory.createLineBorder(Color.BLACK));
        rudderPidP.add(new JLabel("Rudder"));
        rudderPidP.add(new JLabel("P:"));
        rudderPidP.add(rudderPTF);
        rudderPidP.add(new JLabel("I:"));
        rudderPidP.add(rudderITF);
        rudderPidP.add(new JLabel("D:"));
        rudderPidP.add(rudderDTF);

        winchPidP = new JPanel();
        winchPidP.setBorder(BorderFactory.createLineBorder(Color.BLACK));
        winchL = new JLabel("Winch value: ---");
        winchPidP.add(winchL);
        winchPidP.add(winchTF);

        applyB = new JButton("Apply");
        applyB.addActionListener(new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent ae) {
                applyFieldValues();
            }
        });

        contentP = new JPanel();
        contentP.setLayout(new BoxLayout(contentP, BoxLayout.Y_AXIS));
        if (USE_VEL_MULTIPLIER) {
            contentP.add(velMultP);
        }
        contentP.add(thrustPidP);
        contentP.add(rudderPidP);
//        contentP.add(winchPidP);
        contentP.add(applyB);
        getViewport().add(contentP);
        setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
        setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
    }

    public double stringToDouble(String text) {
        double ret = Double.NaN;
        try {
            ret = Double.valueOf(text);
        } catch (NumberFormatException ex) {
        }
        return ret;
    }

    public void applyFieldValues() {
        if (activeVehicle == null) {
            LOGGER.warning("Tried to apply field values to a null vehicle server!");
            return;
        }
        double temp;

        // Thrust PID
        temp = stringToDouble(thrustPTF.getText());
        if (Double.isFinite(temp)) {
            thrustP = temp;
        }
        temp = stringToDouble(thrustITF.getText());
        if (Double.isFinite(temp)) {
            thrustI = temp;
        }
        temp = stringToDouble(thrustDTF.getText());
        if (Double.isFinite(temp)) {
            thrustD = temp;
        }
        // Rudder PID
        temp = stringToDouble(rudderPTF.getText());
        if (Double.isFinite(temp)) {
            rudderP = temp;
        }
        temp = stringToDouble(rudderITF.getText());
        if (Double.isFinite(temp)) {
            rudderI = temp;
        }
        temp = stringToDouble(rudderDTF.getText());
        if (Double.isFinite(temp)) {
            rudderD = temp;
        }
        // Winch
        temp = stringToDouble(winchTF.getText());
        if (Double.isFinite(temp)) {
            winch = temp;
        }

        // Always send in case of communication problems
        activeVehicle.setGains(THRUST_GAINS_AXIS, new double[]{thrustP, thrustI, thrustD}, new FunctionObserver<Void>() {
            public void completed(Void v) {
                LOGGER.fine("Set thrust gains succeeded: Axis [" + THRUST_GAINS_AXIS + "] PID [" + thrustP + ", " + thrustI + ", " + thrustD + "]");
            }

            public void failed(FunctionObserver.FunctionError fe) {
                LOGGER.severe("Set thrust gains failed: Axis [" + THRUST_GAINS_AXIS + "] PID [" + thrustP + ", " + thrustI + ", " + thrustD + "]");
            }
        });
        activeVehicle.setGains(RUDDER_GAINS_AXIS, new double[]{rudderP, rudderI, rudderD}, new FunctionObserver<Void>() {
            public void completed(Void v) {
                LOGGER.fine("Set rudder gains succeeded: Axis [" + RUDDER_GAINS_AXIS + "] PID [" + rudderP + ", " + rudderI + ", " + rudderD + "]");
            }

            public void failed(FunctionObserver.FunctionError fe) {
                LOGGER.severe("Set rudder gains failed: Axis [" + RUDDER_GAINS_AXIS + "] PID [" + rudderP + ", " + rudderI + ", " + rudderD + "]");
            }
        });
        activeVehicle.setGains(WINCH_GAINS_AXIS, new double[]{winch, winch, winch}, new FunctionObserver<Void>() {
            public void completed(Void v) {
                LOGGER.fine("Set winch gains succeeded: Axis [" + WINCH_GAINS_AXIS + "] PID [" + winch + ", " + winch + ", " + winch + "]");
            }

            public void failed(FunctionObserver.FunctionError fe) {
                LOGGER.severe("Set winch gains failed: Axis [" + WINCH_GAINS_AXIS + "] PID [" + winch + ", " + winch + ", " + winch + "]");
            }
        });
    }

    public double getVelocityMultiplier() {
        if (USE_VEL_MULTIPLIER) {
            return stringToDouble(velocityMultF.getText());
        }
        return 1.0;
    }

    public void setProxy(BoatProxy boatProxy) {
        if (activeProxy == boatProxy) {
            return;
        }
        // Stop listening to the old vehicle
        if (activeWinchObserver != null) {
            activeWinchObserver.removeListener(this);
        }

        activeProxy = boatProxy;
        if (activeProxy != null) {
            activeVehicle = boatProxy.getVehicleServer();
            // Retrieve vehicle specific values
            //@todo Ideally we would only do this if the teleop panel is opened

            // Thrust gains
            activeVehicle.getGains(THRUST_GAINS_AXIS, new FunctionObserver<double[]>() {
                public void completed(double[] values) {
                    LOGGER.fine("Get thrust gains succeeded: Axis [" + THRUST_GAINS_AXIS + "] PID [" + values[0] + ", " + values[1] + ", " + values[2] + "]");
                    thrustPTF.setText("" + values[0]);
                    thrustITF.setText("" + values[1]);
                    thrustDTF.setText("" + values[2]);
                }

                public void failed(FunctionObserver.FunctionError fe) {
                    LOGGER.severe("Get thrust gains failed: Axis [" + THRUST_GAINS_AXIS + "]");
                }
            });
            // Rudder gains
            activeVehicle.getGains(RUDDER_GAINS_AXIS, new FunctionObserver<double[]>() {
                public void completed(double[] values) {
                    LOGGER.fine("Get rudder gains succeeded: Axis [" + RUDDER_GAINS_AXIS + "] PID [" + values[0] + ", " + values[1] + ", " + values[2] + "]");
                    rudderPTF.setText("" + values[0]);
                    rudderITF.setText("" + values[1]);
                    rudderDTF.setText("" + values[2]);
                }

                public void failed(FunctionObserver.FunctionError fe) {
                    LOGGER.severe("Get rudder gains failed: Axis [" + RUDDER_GAINS_AXIS + "]");
                }
            });
            // Winch
            activeVehicle.getGains(WINCH_GAINS_AXIS, new FunctionObserver<double[]>() {
                public void completed(double[] values) {
                    LOGGER.fine("Get winch gains succeeded: Axis [" + WINCH_GAINS_AXIS + "] Value [" + values[0] + "]");
                    winchTF.setText("" + values[0]);
                }

                public void failed(FunctionObserver.FunctionError fe) {
                    LOGGER.severe("Get winch gains failed: Axis [" + WINCH_GAINS_AXIS + "]");
                }
            });
            winchL.setText("Winch value: ---");
            activeWinchObserver = Engine.getInstance().getObserverServer().getObserver(activeProxy, WINCH_GAINS_AXIS);
            activeWinchObserver.addListener(this);

            applyB.setEnabled(true);
        } else {
            activeVehicle = null;
            // No vehicle selected, blank out text fields
            thrustPTF.setText("");
            thrustITF.setText("");
            thrustDTF.setText("");
            rudderPTF.setText("");
            rudderITF.setText("");
            rudderDTF.setText("");
            winchTF.setText("");
            applyB.setEnabled(false);
        }
    }

    @Override
    public void eventOccurred(sami.event.InputEvent ie) {
    }

    @Override
    public void newObservation(Observation o) {
        if (activeProxy == null) {
            LOGGER.warning("Received observation from proxy (" + o.getSource() + ") but there is no active proxy");
				} else if (!o.getSource().equals(activeProxy.getProxyName())) {
            LOGGER.warning("Received observation from proxy (" + o.getSource() + ") which is not active proxy (" + activeProxy.getProxyName() + ")");
            return;
        } else {
            winchL.setText("Winch: " + decimalFormat.format(o.getValue()));
        }
    }
}
