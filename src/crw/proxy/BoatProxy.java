package crw.proxy;

import com.perc.mitpas.adi.mission.planning.task.Task;
import crw.Conversion;
import crw.event.input.proxy.ProxyPathCompleted;
import crw.event.input.proxy.ProxyPoseUpdated;
import crw.event.output.proxy.ProxyEmergencyAbort;
import crw.event.output.proxy.ProxyExecutePath;
import crw.event.output.proxy.ProxyGotoPoint;
import crw.event.output.proxy.ProxyResendWaypoints;
import crw.sensor.CrwObserverServer;
import edu.cmu.ri.crw.FunctionObserver;
import edu.cmu.ri.crw.FunctionObserver.FunctionError;
import edu.cmu.ri.crw.ImageListener;
import edu.cmu.ri.crw.PoseListener;
import edu.cmu.ri.crw.SensorListener;
import edu.cmu.ri.crw.VehicleServer.WaypointState;
import edu.cmu.ri.crw.WaypointListener;
import edu.cmu.ri.crw.data.Twist;
import edu.cmu.ri.crw.data.Utm;
import edu.cmu.ri.crw.data.UtmPose;
import edu.cmu.ri.crw.udp.UdpVehicleServer;
import gov.nasa.worldwind.avlist.AVKey;
import gov.nasa.worldwind.geom.LatLon;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.geom.coords.UTMCoord;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.swing.Timer;
import robotutils.Pose3D;
import sami.engine.Engine;
import sami.engine.PlanManager;
import sami.event.InputEvent;
import sami.event.OutputEvent;
import sami.event.TaskComplete;
import sami.event.TaskDelayed;
import sami.event.TaskReleased;
import sami.event.TaskStarted;
import sami.path.Location;
import sami.path.PathUtm;
import sami.proxy.ProxyInt;
import sami.proxy.ProxyListenerInt;

/**
 * @todo Need a flag for autonomous or under human control
 *
 * @author pscerri
 */
public class BoatProxy extends Thread implements ProxyInt {

    private static final Logger LOGGER = Logger.getLogger(BoatProxy.class.getName());
    final int MIN_TIME_BTW_CMDS = 1000; // Required time between sending waypoint update commands to avoid unexpected server behavior (ms)
    // Lossy comms simulation
    final boolean SIMULATE_COMM_LOSS = false;
    final double COMM_LOSS_PROB = 0.9;
    // Pose recorder file for offline analysis
    final boolean RECORD_POSE = false;
    // InputEvent generation rates
    final int EVENT_GENERATION_TIMER = 500; // ms

    public static final int NUM_SENSOR_PORTS = 4;
    // Identifiers
    int _boatNo;
    private final String name;
    private Color color = null;
    // SAMI variables
    // OutputEvents that must occur sequentially (require movement)
    protected OutputEvent curSequentialEvent = null;
    final ArrayList<OutputEvent> sequentialOutputEvents = new ArrayList<OutputEvent>();
    // The resulting InputEvents from sequentialOutputEvents
    final ArrayList<InputEvent> sequentialInputEvents = new ArrayList<InputEvent>();
    // The ordered list of tasks the proxy has been assigned
    final ArrayList<Task> taskList = new ArrayList<Task>();
    Task currentTask = null;
    HashMap<Task, OutputEvent> taskToLastOe = new HashMap<Task, OutputEvent>();
    HashMap<OutputEvent, Task> oeToTask = new HashMap<OutputEvent, Task>();
    protected ArrayList<ProxyListenerInt> listeners = new ArrayList<ProxyListenerInt>();
    protected Hashtable<ProxyListenerInt, Integer> listenerCounter = new Hashtable<ProxyListenerInt, Integer>();
    // ROS update
    UdpVehicleServer _server;
    PoseListener _stateListener;
    SensorListener _sensorListener;
    WaypointListener _waypointListener;
    //@todo need to consolidate all these different pose representations
    UtmPose _pose;
    private Position position = null;
    private UTMCoord utmCoord = null;
    private Location location = null;
    private BufferedImage latestImg = null;
    // Waypoints
    final Queue<UtmPose> _curWaypoints = new LinkedList<UtmPose>();
    final Queue<UtmPose> _futureWaypoints = new LinkedList<UtmPose>();
    Iterable<Position> _curWaypointsPos = null;
    Iterable<Position> _futureWaypointsPos = null;
    // FunctionObserver variables
    final AtomicBoolean autonomyActive = new AtomicBoolean(false);
    // Go slow variables
    private boolean goSlow = false;
    private boolean goSlowExecuting = false;
    Iterator<Position> goSlowWPs = null;
    UtmPose lastSentGoSlowPose = null;
    long timeLastGoSlowSent = 0L;
    long timeLastGoSlowDone = 0L;
    UtmPose goSlowCurrentTarget = null;
    // Go slow configurable variables
    long goSlowRestTime = 20000L;
    long goSlowToWaypointTime = 20000L;
    double maxWPDist = 30.0;
    // Lossy comms simulation
    final Random COMM_LOSS_RANDOM = new Random(0L);
    // Pose recorder file for offline analysis
    private PrintWriter poseWriter;
    // InputEvent generation
    final AtomicBoolean sendEvent = new AtomicBoolean(true);
    long lastTime = -1;

    // End stuff for simulated data creation
    public BoatProxy(final String name, Color color, final int boatNo, InetSocketAddress addr) {

        if (SIMULATE_COMM_LOSS) {
            LOGGER.info("Boat proxy with COMM_LOSS_PROB: " + COMM_LOSS_PROB + ", name: " + name + ", color: " + color + ", addr: " + addr);
        } else {
            LOGGER.info("Boat proxy created with name: " + name + ", color: " + color + ", addr: " + addr);
        }

        if (RECORD_POSE) {
            try {
                String filename = new SimpleDateFormat("yyyyMMddhhmmss'_" + name + ".txt'").format(new Date());
                poseWriter = new PrintWriter(new File(filename));
            } catch (FileNotFoundException ex) {
                Logger.getLogger(BoatProxy.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        Timer stateTimer = new Timer(EVENT_GENERATION_TIMER, new ActionListener() {

            @Override
            public void actionPerformed(ActionEvent ae) {
                sendEvent.set(true);
            }
        });
        stateTimer.start();

        // this.masterURI = masterURI;
        this.name = name;
        this.color = color;

        //Initialize the boat by initalizing a proxy server for it
        // Connect to boat
        _boatNo = boatNo;

        if (addr == null) {
            LOGGER.severe("INetAddress is null!");
        }

        _server = new UdpVehicleServer(addr);

        final BoatProxy bp = this;

        _stateListener = new PoseListener() {
            public void receivedPose(UtmPose upwcs) {

                if (RECORD_POSE) {
                    // Logger.getLogger(this.getClass().getName()).log(Level.INFO, "Boat pose update", this);
                    poseWriter.write(new SimpleDateFormat("yyyy-MM-dd-hh.mm.ss.SSS'\t" + upwcs.toString() + "\n'").format(new Date()));
                    poseWriter.flush();
                }

                _pose = upwcs.clone();

                // System.out.println("Pose: [" + _pose.pose.position.x + ", " + _pose.pose.position.y + "], zone = " + _pose.utm.zone);
                try {

                    int longZone = _pose.origin.zone;

                    // Convert hemisphere to arbitrary worldwind codes
                    // Notice that there is a "typo" in South that exists in the WorldWind code
                    // String wwHemi = (_pose.origin.isNorth) ? "gov.nasa.worldwind.avkey.North" : "gov.nasa.worldwdind.avkey.South";
                    String wwHemi = (_pose.origin.isNorth) ? AVKey.NORTH : AVKey.SOUTH;

                    // Fill in UTM data structure
                    // System.out.println("Converting from " + longZone + " " + wwHemi + " " + _pose.pose.getX() + " " + _pose.pose.getY());
                    UTMCoord boatPos = UTMCoord.fromUTM(longZone, wwHemi, _pose.pose.getX(), _pose.pose.getY());

                    LatLon latlon = new LatLon(boatPos.getLatitude(), boatPos.getLongitude());

                    // System.out.println("boatPos " + boatPos.getLatitude() + " " +  boatPos.getLongitude() + " latlon " + latlon.latitude.degrees + " " + latlon.longitude.degrees + " " + latlon);
                    Position p = new Position(latlon, 0.0);

                    // Update state variables
                    position = p;
                    utmCoord = boatPos;
                    location = Conversion.positionToLocation(position);

                    for (ProxyListenerInt boatProxyListener : listeners) {
                        boatProxyListener.poseUpdated();
                    }

                } catch (Exception e) {
                    LOGGER.warning("BoatProxy: Invalid pose received: " + e + " Pose: [" + _pose.pose.getX() + ", " + _pose.pose.getY() + "], zone = " + _pose.origin.zone);
                    // e.printStackTrace();
                }

                // Send out event update
                if (sendEvent.get()) {
                    ProxyPoseUpdated ie = new ProxyPoseUpdated(null, null, bp);
                    for (ProxyListenerInt boatProxyListener : listeners) {
                        boatProxyListener.eventOccurred(ie);
                    }
                    sendEvent.set(false);
                }
            }
        };

        LOGGER.info("New boat created, boat # " + _boatNo);

        //add Listeners
        _server.addPoseListener(_stateListener, null);

        _server.addWaypointListener(new WaypointListener() {
            public void waypointUpdate(WaypointState ws) {

                if (ws.equals(WaypointState.DONE)) {

                    LOGGER.log(Level.FINE, "BoatProxy " + getName() + " got waypoint update " + ws + " (WaypointState.DONE)");

                    // Handle the go slow
                    if (goSlowExecuting) {
                        timeLastGoSlowDone = System.currentTimeMillis();
                        goSlowRun();
                    }
                    // This is intentionally rechecking, because goSlowRun() will change the boolean
                    // if it is done
                    if (!goSlowExecuting) {
                        // Notify listeners
                        for (ProxyListenerInt boatProxyListener : listeners) {
                            boatProxyListener.waypointsComplete();
                        }
                    }
                    if (sequentialOutputEvents.isEmpty()) {
                        LOGGER.severe("Got a waypoints done message, but sequentialOutputEvents is empty!");
                    } else {
                        // Remove the OutputEvent that held this path
                        OutputEvent oe = sequentialOutputEvents.remove(0);
                        // Send out the InputEvent assocaited with this path
                        InputEvent ie = sequentialInputEvents.remove(0);

                        updateWaypoints(true, true);

                        LOGGER.log(Level.FINE, "BoatProxy " + getName() + " completed sequential OE " + oe + ", sending out IE " + ie);
                        for (ProxyListenerInt boatProxyListener : listeners) {
                            boatProxyListener.eventOccurred(ie);
                        }
                        if (!sequentialOutputEvents.isEmpty()) {
                            LOGGER.log(Level.FINE, "Sequential OE list is not empty, do the next one!");
                            sendCurrentWaypoints();
                        }
                    }
                }

            }
        }, null);

        _server.addImageListener(new ImageListener() {
            public void receivedImage(byte[] ci) {
                // Take a picture, and put the resulting image into the panel
                try {
                    BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(ci));
                    LOGGER.log(Level.FINE, "Got image ... ");

                    if (image != null) {
                        // Flip the image vertically
                        AffineTransform tx = AffineTransform.getScaleInstance(1, -1);
                        tx.translate(0, -image.getHeight(null));
                        AffineTransformOp op = new AffineTransformOp(tx, AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
                        image = op.filter(image, null);

                        if (image == null) {
                            LOGGER.warning("Failed to decode image.");
                        }

                        // ImagePanel.addImage(image, _pose);
                        latestImg = image;
                    } else {
                        LOGGER.warning("Image was null in receivedImage");
                    }
                } catch (IOException ex) {
                    LOGGER.warning("Failed to decode image: " + ex);
                }

            }
        }, null);

        for (int i = 0; i < NUM_SENSOR_PORTS; i++) {
            ((CrwObserverServer) Engine.getInstance().getObserverServer()).createObserver(this, i);
        }

        // startCamera();
    }

    @Override
    public int getProxyId() {
        return _boatNo;
    }

    @Override
    public String getProxyName() {
        return name;
    }

    @Override
    public void addListener(ProxyListenerInt l) {
        if (!listenerCounter.containsKey(l)) {
            LOGGER.log(Level.FINE, "First addition of listener " + l);
            listenerCounter.put(l, 1);
            listeners.add(l);
        } else {
            LOGGER.log(Level.FINE, "Count is now " + (listenerCounter.get(l) + 1) + " for " + l);
            listenerCounter.put(l, listenerCounter.get(l) + 1);
        }
    }

    @Override
    public void removeListener(ProxyListenerInt l) {
        if (!listenerCounter.containsKey(l)) {
            LOGGER.log(Level.WARNING, "Tried to remove ProxyListener that is not in the list!" + l);
        } else if (listenerCounter.get(l) == 1) {
            LOGGER.log(Level.FINE, "Last count of listener, removing " + l);
            listenerCounter.remove(l);
            listeners.remove(l);
        } else {
            LOGGER.log(Level.FINE, listenerCounter.get(l) - 1 + " counts remaining for " + l);
            listenerCounter.put(l, listenerCounter.get(l) - 1);
        }
    }

    @Override
    public void run() {
        // startCamera();
    }

    @Override
    public void handleEvent(OutputEvent oe, Task task) {
        if (task != null && task != currentTask) {
            LOGGER.severe("Proxy [" + this + "] asked to handle event [" + oe + "] for a task [" + task + "] that is not the current task [" + currentTask + "] - ignoring");
            return;
        } else {
            LOGGER.fine("Proxy [" + this + "] asked to handle event [" + oe + "] for task [" + task + "], current task is [" + currentTask + "]");
        }

        int index;
        if (task != null && taskToLastOe.containsKey(task)) {
            // This is an OE for the currently executing task; append after last event for this task
            LOGGER.fine("\tAppending task event after last OE for the task");
            index = sequentialOutputEvents.indexOf(taskToLastOe.get(task)) + 1;

            // What if not in seq OE? Should be ok bec result is -1 + 1
            taskToLastOe.put(task, oe);
        } else if (task != null) {
            // This is the first OE for the currently executing task; start it now
            LOGGER.fine("\tFirst OE for this task");
            index = 0;
            taskToLastOe.put(task, oe);
        } else {
            // If there is no task associated with the OE, put it at the end of the list
            LOGGER.fine("\tAppending no-task event to end of OE list");
            index = sequentialOutputEvents.size();
        }
        oeToTask.put(oe, task);

        LOGGER.fine("\t\tAdding to index [" + index + "] in list of " + sequentialOutputEvents.size());

        // If we have a current task but the front OE does not correspond to it, don't do it
        //  If this happens, the front OE should be a no-task
        //  Unless it is a ProxyResendWaypoints or ProxyEmergencyAbort event
        boolean sendWps = false;
        if (oe instanceof ProxyExecutePath) {
            sequentialOutputEvents.add(index, oe);
            sequentialInputEvents.add(index, new ProxyPathCompleted(oe.getId(), oe.getMissionId(), this));
        } else if (oe instanceof ProxyGotoPoint) {
            sequentialOutputEvents.add(index, oe);
            sequentialInputEvents.add(index, new ProxyPathCompleted(oe.getId(), oe.getMissionId(), this));
        } else if (oe instanceof ProxyEmergencyAbort) {
            // Clear out all events and stop
            LOGGER.severe("Handling ProxyEmergencyAbort! sequentialOutputEvents were: " + sequentialOutputEvents + ", sequentialInputEvents were: " + sequentialInputEvents);
            sequentialOutputEvents.clear();
            sequentialInputEvents.clear();
            sendWps = true;
        } else if (oe instanceof ProxyResendWaypoints) {
            sendWps = true;
        } else if (oe instanceof TaskComplete) {
            if (task == currentTask && currentTask == taskList.get(0)) {
                // Update task list and currentTask
                taskList.remove(0);
                if (!taskList.isEmpty()) {
                    // If there is a next task, start it
                    currentTask = taskList.get(0);
                    PlanManager pm = Engine.getInstance().getPlanManager(currentTask);
                    pm.eventGenerated(new TaskStarted(pm.missionId, currentTask));
                } else {
                    // Otherwise set currentTask to null so non-task OEs will run
                    currentTask = null;
                }
                sendWps = true;
            } else if (task != currentTask) {
                LOGGER.severe("Got TaskComplete event for task [" + task + "], but current task is [" + currentTask + "]");
            } else if (currentTask == taskList.get(0)) {
                LOGGER.severe("Got TaskComplete event for task [" + task + "], but currentTask [" + currentTask + "] does not match first task [" + taskList.get(0) + "]");
            }
        } else {
            LOGGER.severe("Can't handle OutputEvent of class " + oe.getClass().getSimpleName());
        }

        // Update proxy's waypoints
        updateWaypoints(true, true);

        /// not sure about this check  - what about index 0 with non matching task
        if (sequentialOutputEvents.size() == 1
                || index == 0
                || sendWps) {
            // Send waypoints if we modified the first set of waypoints or want to resend them due to spotty comms
            LOGGER.fine("Sending waypoints to finish handling event");
            sendCurrentWaypoints();
        }

        LOGGER.fine("After handle event: sequentialOutputEvents [" + sequentialOutputEvents.toString() + "], curSequentialEvent [" + curSequentialEvent + "], taskList [" + taskList.toString() + "], currentTask [" + currentTask + "]");
    }

    @Override
    public OutputEvent getCurrentEvent() {
        return curSequentialEvent;
    }

    @Override
    public ArrayList<OutputEvent> getEvents() {
        return sequentialOutputEvents;
    }

    @Override
    public void abortEvent(UUID eventId) {
        LOGGER.info("Abort Event called with event id [" + eventId + "] on proxy [" + toString() + "]");
        int numRemoved = 0;
        ArrayList<OutputEvent> outputEventsToRemove = new ArrayList<OutputEvent>();
        ArrayList<InputEvent> inputEventsToRemove = new ArrayList<InputEvent>();
        boolean removeFirst = false, removedEvent = false;
        for (int i = 0; i < sequentialOutputEvents.size(); i++) {
            if (sequentialOutputEvents.get(i).getId() == null) {
                LOGGER.warning("Output event [" + sequentialOutputEvents.get(i) + "] on proxy [" + toString() + "] has no event id!");
            } else if (sequentialOutputEvents.get(i).getId().equals(eventId)) {
                outputEventsToRemove.add(sequentialOutputEvents.get(i));
                inputEventsToRemove.add(sequentialInputEvents.get(i));
                if (i == 0) {
                    // We need to remove the currently executing task
                    removeFirst = true;
                }
                numRemoved++;
            }
        }
        for (OutputEvent outputEvent : outputEventsToRemove) {
            sequentialOutputEvents.remove(outputEvent);
        }
        for (InputEvent inputEvent : inputEventsToRemove) {
            sequentialInputEvents.remove(inputEvent);
        }

        updateWaypoints(removeFirst, numRemoved > 0);
        if (removeFirst) {
            // If we modified the first set of waypoints, start them
            LOGGER.info("\t Removed " + numRemoved + " events while aborting eventId [" + eventId + "] on proxy [" + toString() + "], including current event");
            sendCurrentWaypoints();
        } else {
            LOGGER.info("\t Removed " + numRemoved + " events while aborting eventId [" + eventId + "] on proxy [" + toString() + "], but not the current event");
        }
    }

    @Override
    public void addChildTask(Task parentTask, Task childTask) {
        // Add child task immediately after parent task
        if (!taskList.contains(childTask)) {
            if (taskList.contains(parentTask)) {
                int index = taskList.indexOf(parentTask);
                taskList.add(index + 1, childTask);
            } else {
                LOGGER.warning("Tried to add child task " + childTask + " to proxy " + toString() + ". but parent task " + parentTask + " is not in task list");
            }
        } else {
            LOGGER.warning("Tried to add child task " + childTask + " to proxy " + toString() + " twice");
        }
    }

    @Override
    public Task getCurrentTask() {
        return currentTask;
    }

    @Override
    public ArrayList<Task> getTasks() {
        return (ArrayList<Task>) taskList.clone();
    }

    @Override
    public ArrayList<InputEvent> setTasks(ArrayList<Task> tasks) {
        LOGGER.fine("Set tasks for proxy [" + this + "] from [" + taskList.toString() + "] to [" + tasks.toString() + "]");
        // Create lists for TaskDelayed and TaskReleased events
        ArrayList<InputEvent> taskEvents = new ArrayList<InputEvent>();
        // TaskDelayed
        if (currentTask != null
                && !tasks.isEmpty()
                && currentTask != tasks.get(0)
                && tasks.contains(currentTask)) {
            // The current task is being pushed back in the task order
            LOGGER.fine("Proxy [" + this + "] interrupted first task");
            PlanManager pm = Engine.getInstance().getPlanManager(currentTask);
            taskEvents.add(new TaskDelayed(pm.missionId, currentTask));
        }
        // TaskReleased
        for (Task task : taskList) {
            if (!tasks.contains(task)) {
                LOGGER.fine("Proxy [" + this + "] adding TaskReleased event for task [" + task + "]");
                PlanManager pm = Engine.getInstance().getPlanManager(task);
                taskEvents.add(new TaskReleased(pm.missionId, this, task));
            }
        }
        // Check if we have a new current task we should send begin executing
        boolean changedFirstTask = false;
        if ((tasks.isEmpty() && currentTask != null)
                || (!tasks.isEmpty() && currentTask != tasks.get(0))) {
            LOGGER.fine("Proxy [" + this + "] changed first task");
            changedFirstTask = true;
            currentTask = tasks.get(0);
        }
        // Update waypoint list
        taskList.clear();
        taskList.addAll(tasks);

        // update current task!
        if (changedFirstTask) {
            // Should stop the boat unless it has no tasks, but has no-task events
            updateWaypoints(true, true);
            sendCurrentWaypoints();
            PlanManager pm = Engine.getInstance().getPlanManager(currentTask);
            taskEvents.add(new TaskStarted(pm.missionId, currentTask));
        } else {
            updateWaypoints(false, true);
        }

        LOGGER.fine("After set tasks: sequentialOutputEvents [" + sequentialOutputEvents.toString() + "], curSequentialEvent [" + curSequentialEvent + "], taskList [" + taskList.toString() + "], currentTask [" + currentTask + "]");
        LOGGER.fine("After set tasks: returning taskEvent [" + taskEvents + "]");
        return taskEvents;
    }

    @Override
    public void taskCompleted(Task task) {
        if (currentTask != task) {
            LOGGER.severe("Received task complete for [" + task + "], but the current task is [" + currentTask + "]");
            return;
        }
        // Consume completed task
        taskList.remove(0);
        Engine.getInstance().taskCompleted(task);

        // Update current task
        if (!taskList.isEmpty()) {
            currentTask = taskList.get(0);
            // Send out a Task Started event
            PlanManager pm = Engine.getInstance().getPlanManager(currentTask);
            pm.eventGenerated(new TaskStarted(pm.missionId, currentTask));

            // Update proxy's waypoints
            updateWaypoints(true, true);
            sendCurrentWaypoints();
        } else {
            currentTask = null;
        }

        //@todo What if we have delayed this and already have events for this task????
    }

    @Override
    public void abortMission(UUID missionId) {
        LOGGER.info("Abort Mission called with mission id [" + missionId + "] on proxy [" + toString() + "]");
        int numRemoved = 0;
        ArrayList<OutputEvent> outputEventsToRemove = new ArrayList<OutputEvent>();
        ArrayList<InputEvent> inputEventsToRemove = new ArrayList<InputEvent>();
        boolean removeFirst = false, removedEvent = false;
        for (int i = 0; i < sequentialOutputEvents.size(); i++) {
            if (sequentialOutputEvents.get(i).getMissionId() == null) {
                LOGGER.warning("Output event [" + sequentialOutputEvents.get(i) + "] on proxy [" + toString() + "] has no mission id!");
            } else if (sequentialOutputEvents.get(i).getMissionId().equals(missionId)) {
                outputEventsToRemove.add(sequentialOutputEvents.get(i));
                inputEventsToRemove.add(sequentialInputEvents.get(i));
                if (i == 0) {
                    // We need to remove the currently executing task
                    removeFirst = true;
                }
                numRemoved++;
            }
        }
        for (OutputEvent outputEvent : outputEventsToRemove) {
            sequentialOutputEvents.remove(outputEvent);
        }
        for (InputEvent inputEvent : inputEventsToRemove) {
            sequentialInputEvents.remove(inputEvent);
        }

        updateWaypoints(removeFirst, removedEvent);
        if (removeFirst) {
            // If we modified the first set of waypoints, start them
            LOGGER.info("Removed " + numRemoved + " events while aborting missionId [" + missionId + "] on proxy [" + toString() + "], including current event");
            sendCurrentWaypoints();
        } else {
            LOGGER.info("Removed " + numRemoved + " events while aborting missionId [" + missionId + "] on proxy [" + toString() + "], but not the current event");
        }
    }

    public OutputEvent getCurSequentialEvent() {
        return curSequentialEvent;
    }

    public int getBoatNo() {
        return _boatNo;
    }

    public UtmPose getUtmPose() {
        return _pose;
    }

    public Position getPosition() {
        return position;
    }

    public Location getLocation() {
        return location;
    }

    public Queue<UtmPose> getCurrentWaypoints() {
        return _curWaypoints;
    }

    public Iterable<Position> getCurrentWaypointsAsPositions() {
        return _curWaypointsPos;
    }

    public Queue<UtmPose> getFutureWaypoints() {
        return _futureWaypoints;
    }

    public Iterable<Position> getFutureWaypointsAsPositions() {
        return _futureWaypointsPos;
    }

    public boolean isGoSlow() {
        return goSlow;
    }

    public void setGoSlow(boolean goSlow) {
        this.goSlow = goSlow;
    }

    public void addImageListener(ImageListener l) {
        _server.addImageListener(l, null);
        startCamera();
    }

    public void addSensorListener(int channel, SensorListener l) {
        _server.addSensorListener(channel, l, null);

        // @todo This only allows one sensor, generalize (but I think this is only for the fake data ...)
        _sensorListener = l;

        // System.out.println("Setting SENSOR LISTENER TO: " + l);
    }

    public void addPoseListener(PoseListener l) {
        _server.addPoseListener(l, null);
    }

    public void addWaypointListener(WaypointListener l) {
        _server.addWaypointListener(l, null);
    }

    public void startCamera() {

        (new Thread() {
            public void run() {

                try {
                    LOGGER.log(Level.FINE, "SLEEPING BEFORE CAMERA START");
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                }
                LOGGER.log(Level.FINE, "DONE SLEEPING BEFORE CAMERA START");

                _server.startCamera(0, 30.0, 640, 480, null);

                LOGGER.log(Level.FINE, "Image listener started");
            }
        }).start();
    }

    /**
     * Updates the variables and lists used to execute and visualize current and
     * future waypoints from the output event list. Note this does not actually
     * send waypoints to the server
     *
     * @param updateCurrent Whether to update the current event and waypoint
     * list
     * @param updateFuture Whether to update the waypoint list to be executed
     * after the current event's
     */
    public void updateWaypoints(boolean updateCurrent, boolean updateFuture) {
        // Current waypoint must be first in the list AND match the currentTask if there is one
        if (updateCurrent) {
            LOGGER.fine("Proxy [" + this + "]; updating current waypoint: oeToTask [" + oeToTask + "]");
            if (!sequentialOutputEvents.isEmpty()) {
                LOGGER.fine("Proxy [" + this + "]; current task [" + currentTask + "] and first OE [" + sequentialOutputEvents.get(0) + "]");
            }
            _curWaypointsPos = null;
            _curWaypoints.clear();
            curSequentialEvent = null;

            if (sequentialOutputEvents.isEmpty()) {
                // Have no events to process
                LOGGER.fine("Proxy [" + this + "] has no current WP, sequentialOutputEvents is empty");
            } else if (currentTask != null && oeToTask.get(sequentialOutputEvents.get(0)) != currentTask) {
                // First event doesn't belong to current event
                LOGGER.fine("Proxy [" + this + "] has no current WP, first event [" + sequentialOutputEvents.get(0) + "] is not for current task [" + currentTask + "]");
            } else {
                LOGGER.fine("Proxy [" + this + "] updating current WP with current task [" + currentTask + "] and first event [" + sequentialOutputEvents.get(0) + "]");
                // We have no current task or first event belongs to current task
                if (sequentialOutputEvents.get(0) instanceof ProxyExecutePath) {
                    ProxyExecutePath executePath = (ProxyExecutePath) sequentialOutputEvents.get(0);
                    if (executePath.getProxyPaths().containsKey(this)
                            && executePath.getProxyPaths().get(this) instanceof PathUtm) {
                        curSequentialEvent = sequentialOutputEvents.get(0);
                        PathUtm pathUtm = (PathUtm) executePath.getProxyPaths().get(this);
                        ArrayList<Position> positions = new ArrayList<Position>();
                        for (Location waypoint : pathUtm.getPoints()) {
                            positions.add(Conversion.locationToPosition(waypoint));
                        }
                        _curWaypointsPos = positions;
                        for (Position position : positions) {
                            UTMCoord utm = UTMCoord.fromLatLon(position.latitude, position.longitude);
                            UtmPose pose = new UtmPose(new Pose3D(utm.getEasting(), utm.getNorthing(), 0.0, 0.0, 0.0, 0.0), new Utm(utm.getZone(), utm.getHemisphere().contains("North")));
                            _curWaypoints.add(pose);
                        }
                        if (_curWaypoints.isEmpty()) {
                            // Add current position so waypoint complete fires
                            LOGGER.info("Constructed curWaypoints was empty, adding current boat position");
                            UtmPose pose = new UtmPose(new Pose3D(utmCoord.getEasting(), utmCoord.getNorthing(), 0.0, 0.0, 0.0, 0.0), new Utm(utmCoord.getZone(), utmCoord.getHemisphere().contains("North")));
                            _curWaypoints.add(pose);
                        }
                    } else {
                        if (!executePath.getProxyPaths().containsKey(this)) {
                            LOGGER.severe("Proxy Paths has no entry for this proxy: " + this + ": " + executePath.getProxyPaths());
                        } else {
                            LOGGER.severe("Can't handle Path of class " + executePath.getProxyPaths().get(this).getClass().getSimpleName());
                        }
                    }
                } else if (sequentialOutputEvents.get(0) instanceof ProxyGotoPoint) {
                    ProxyGotoPoint gotoPoint = (ProxyGotoPoint) sequentialOutputEvents.get(0);
                    if (gotoPoint.getProxyPoints().containsKey(this)) {
                        curSequentialEvent = sequentialOutputEvents.get(0);
                        Location location = gotoPoint.getProxyPoints().get(this);
                        ArrayList<Position> positions = new ArrayList<Position>();
                        positions.add(Conversion.locationToPosition(location));

                        _curWaypointsPos = positions;
                        for (Position position : positions) {
                            UTMCoord utm = UTMCoord.fromLatLon(position.latitude, position.longitude);
                            UtmPose pose = new UtmPose(new Pose3D(utm.getEasting(), utm.getNorthing(), 0.0, 0.0, 0.0, 0.0), new Utm(utm.getZone(), utm.getHemisphere().contains("North")));
                            _curWaypoints.add(pose);
                        }
                    } else {
                        LOGGER.severe("Proxy points has no entry for this proxy: " + this + ": " + gotoPoint.getProxyPoints());
                    }
                } else {
                    LOGGER.severe("Can't handle OutputEvent of class " + sequentialOutputEvents.get(0).getClass().getSimpleName());
                }
            }
        }

        // Future
        if (updateFuture) {
            _futureWaypoints.clear();
            _futureWaypointsPos = null;

            boolean includeFirst = !sequentialOutputEvents.isEmpty()
                    && currentTask != null
                    && oeToTask.get(sequentialOutputEvents.get(0)) != currentTask;
            if (sequentialInputEvents.isEmpty()
                    || (sequentialOutputEvents.size() == 1 && !includeFirst)) {
                LOGGER.fine("No future WPs, includeFirst [" + includeFirst + "] and sequentialInputEvents size [" + sequentialInputEvents.size() + "]");
            } else {
                int startIndex = includeFirst ? 0 : 1;
                LOGGER.fine("Updating future WPs, starting at index [" + startIndex + "] with sequentialOutputEvents of size [" + sequentialOutputEvents.size() + "]");
                ArrayList<Position> positions = new ArrayList<Position>();
                for (int i = startIndex; i < sequentialOutputEvents.size(); i++) {
                    if (sequentialOutputEvents.get(i) instanceof ProxyExecutePath) {
                        ProxyExecutePath executePath = (ProxyExecutePath) sequentialOutputEvents.get(i);
                        if (executePath.getProxyPaths().containsKey(this)
                                && executePath.getProxyPaths().get(this) instanceof PathUtm) {
                            PathUtm pathUtm = (PathUtm) executePath.getProxyPaths().get(this);
                            for (Location waypoint : pathUtm.getPoints()) {
                                positions.add(Conversion.locationToPosition(waypoint));
                            }
                        } else {
                            if (!executePath.getProxyPaths().containsKey(this)) {
                                LOGGER.severe("Proxy paths [" + executePath.getProxyPaths() + "] has no entry for proxy [" + toString() + "]!");
                            } else {
                                LOGGER.severe("Can't handle Path of class [" + executePath.getProxyPaths().get(this).getClass().getSimpleName() + "]!");
                            }
                        }
                    } else if (sequentialOutputEvents.get(i) instanceof ProxyGotoPoint) {
                        ProxyGotoPoint gotoPoint = (ProxyGotoPoint) sequentialOutputEvents.get(i);
                        if (gotoPoint.getProxyPoints().containsKey(this)) {
                            Location location = gotoPoint.getProxyPoints().get(this);
                            positions.add(Conversion.locationToPosition(location));
                        } else {
                            LOGGER.severe("Proxy points [" + gotoPoint.getProxyPoints() + "] has no entry for proxy [" + toString() + "]!");
                        }
                    } else {
                        LOGGER.severe("BoatProxy can't handle OutputEvent of class [" + sequentialOutputEvents.get(i).getClass().getSimpleName() + "]");
                    }
                }
                _futureWaypointsPos = positions;
                for (Position position : positions) {
                    UTMCoord utm = UTMCoord.fromLatLon(position.latitude, position.longitude);
                    UtmPose pose = new UtmPose(new Pose3D(utm.getEasting(), utm.getNorthing(), 0.0, 0.0, 0.0, 0.0), new Utm(utm.getZone(), utm.getHemisphere().contains("North")));
                    _futureWaypoints.add(pose);
                }
            }
        }

        // Notify listeners
        for (ProxyListenerInt boatProxyListener : listeners) {
            boatProxyListener.waypointsUpdated();
        }
    }

    /**
     * Takes in an output event: if the current sequential output event has the
     * same UUID, it is replaced with the passed in event: if not, it is
     * inserted at the beginning of the sequential list and the proxy begins
     * executing the event
     *
     * @param updatedEvent The event to replace or insert in front of the
     * current sequential event
     * @return Whether the event was handled
     */
    public boolean updateCurrentSeqEvent(OutputEvent updatedEvent) {
        LOGGER.fine("updateCurrentSeqEvent with updatedEvent: " + updatedEvent + "sequentialOutputEvents: " + sequentialOutputEvents);
        boolean handled = true;
        if (curSequentialEvent != null && curSequentialEvent.getId().equals(updatedEvent.getId())) {
            LOGGER.fine("Replace");
            // The updated event is the currently executing event, replace it
            if (updatedEvent instanceof ProxyExecutePath) {
                // Replace the existing event with the updated event and begin executing it
                sequentialOutputEvents.set(0, updatedEvent);
                updateWaypoints(true, false);
                sendCurrentWaypoints();
            } else {
                LOGGER.severe("BoatProxy can't handle OutputEvent of class [" + updatedEvent.getClass().getSimpleName() + "]!");
                handled = false;
            }
        } else {
            LOGGER.fine("Insert at 0 while size is " + sequentialOutputEvents.size());
            // The updated event is not the currently executing event, insert it at the beginning of the list
            if (updatedEvent instanceof ProxyExecutePath) {
                // Insert the event and begin executing it
                sequentialOutputEvents.add(0, updatedEvent);
                sequentialInputEvents.add(0, new ProxyPathCompleted(updatedEvent.getId(), updatedEvent.getMissionId(), this));
                updateWaypoints(true, true);
                sendCurrentWaypoints();
            } else {
                LOGGER.severe("BoatProxy can't handle OutputEvent of class [" + updatedEvent.getClass().getSimpleName() + "]!");
                handled = false;
            }
        }
        return handled;
    }

    /**
     * Stops and removes the current sequential output event being executed and
     * then begins the next (if applicable)
     */
    public void cancelCurrentSeqEvent() {
        if (sequentialOutputEvents.isEmpty()) {
            return;
        }
        OutputEvent removedEvent = sequentialOutputEvents.remove(0);
        sequentialInputEvents.remove(0);

        updateWaypoints(true, true);
        sendCurrentWaypoints();
    }

    public void sendCurrentWaypoints() {
        LOGGER.fine("sendCurrentWaypoints");
        if (_curWaypoints.isEmpty()) {
            LOGGER.fine("stopWaypoints");
            // There weren't any more waypoints - stop the proxy
            // Stop proxy as opposed to sending empty list of waypoints because 
            //  that will make the system think it finished assigned waypoints
            if (SIMULATE_COMM_LOSS && COMM_LOSS_RANDOM.nextDouble() <= COMM_LOSS_PROB) {
                LOGGER.fine("*** Dropping _server.stopWaypoints");
            } else {
                LOGGER.fine("*** Sending _server.stopWaypoints");
                LOGGER.info("BoatProxy [" + toString() + "] stopWaypoints");

                // Make sure we don't send waypoint commands too fast - stop and go commands can get out of order otherwise
                checkAndSleepForCmd();
                _server.stopWaypoints(null);
            }
        } else {
            if (goSlow && _curWaypointsPos.iterator().hasNext()) {
                LOGGER.fine("GO SLOW MODE");
                sendCurrentWaypointsGoSlow();
            } else if (!goSlow) {
                LOGGER.fine("GO FAST MODE");
                sendCurrentWaypointsGoFast();
            }
        }
    }

    private void sendCurrentWaypointsGoFast() {
        LOGGER.fine("sendCurrentWaypointsGoFast: " + _curWaypoints.toString());
        activateAutonomy(true);
        if (SIMULATE_COMM_LOSS && COMM_LOSS_RANDOM.nextDouble() <= COMM_LOSS_PROB) {
            LOGGER.fine("*** Dropping _server.startWaypoints");
        } else {
            LOGGER.fine("*** Sending _server.startWaypoints");
            LOGGER.info("BoatProxy [" + toString() + "] startWaypoints [" + _curWaypoints.toString() + "]");

            // Make sure we don't send waypoint commands too fast - stop and go commands can get out of order otherwise
            checkAndSleepForCmd();
            _server.startWaypoints(_curWaypoints.toArray(new UtmPose[_curWaypoints.size()]), "POINT_AND_SHOOT", new FunctionObserver() {
                int completedCounter = 0;

                public void completed(Object v) {
                    LOGGER.log(Level.FINE, "Start waypoints succeeded");
                }

                public void failed(FunctionError fe) {
                    // @todo Do something when start waypoints fails
                    LOGGER.severe("Start waypoints failed");
                }
            });
        }
    }

    private void sendCurrentWaypointsGoSlow() {
        goSlowWPs = _curWaypointsPos.iterator();
        goSlowExecuting = true;
        goSlowRun();
    }

    private void goSlowRun() {

        if (System.currentTimeMillis() - timeLastGoSlowSent > goSlowRestTime
                && System.currentTimeMillis() - timeLastGoSlowDone > goSlowRestTime) {

            _curWaypoints.clear();

            try {

                // Get a new target point, if we don't have one
                if (goSlowCurrentTarget == null) {
                    Position position = goSlowWPs.next();
                    UTMCoord utm = UTMCoord.fromLatLon(position.latitude, position.longitude);
                    goSlowCurrentTarget = new UtmPose(new Pose3D(utm.getEasting(), utm.getNorthing(), 0.0, 0.0, 0.0, 0.0), new Utm(utm.getZone(), utm.getHemisphere().contains("North")));
                }

                // If we are too far from that target point, pick some intermediate point
                double dist = _pose.pose.getEuclideanDistance(goSlowCurrentTarget.pose);
                LOGGER.log(Level.FINE, "DISTANCE IS " + dist);
                UtmPose realTarget = null;

                if (dist > maxWPDist) {
                    LOGGER.log(Level.FINE, "Creating an intermediate point in go slow");
                    double x = _pose.pose.getX() + (maxWPDist / dist) * (goSlowCurrentTarget.pose.getX() - _pose.pose.getX());
                    double y = _pose.pose.getY() + (maxWPDist / dist) * (goSlowCurrentTarget.pose.getY() - _pose.pose.getY());
                    realTarget = new UtmPose(new Pose3D(x, y, _pose.pose.getZ(), _pose.pose.getRotation()), new Utm(utmCoord.getZone(), utmCoord.getHemisphere().contains("North")));
                    _curWaypoints.add(realTarget);
                } else {
                    // We are done with this point
                    _curWaypoints.add(goSlowCurrentTarget);
                    goSlowCurrentTarget = null;
                }

                timeLastGoSlowSent = System.currentTimeMillis();

                // This thread monitors whether it took too long to get to the waypoint
                (new Thread() {
                    public void run() {
                        try {
                            sleep(goSlowToWaypointTime);
                        } catch (InterruptedException e) {
                        }
                        if (System.currentTimeMillis() - timeLastGoSlowSent > goSlowToWaypointTime) {
                            LOGGER.log(Level.FINE, "Failed to reach waypoint in time, " + goSlowRestTime + " resting");

                            if (SIMULATE_COMM_LOSS && COMM_LOSS_RANDOM.nextDouble() <= COMM_LOSS_PROB) {
                                LOGGER.fine("*** Dropping _server.stopWaypoints");
                            } else {
                                LOGGER.fine("*** Sending _server.stopWaypoints");
                                LOGGER.info("BoatProxy [" + toString() + "] stopWaypoints");

                                // Make sure we don't send waypoint commands too fast - stop and go commands can get out of order otherwise
                                checkAndSleepForCmd();

                                _server.stopWaypoints(new FunctionObserver<Void>() {
                                    public void completed(Void v) {
                                        LOGGER.log(Level.FINE, "Resting waypoints due to too long");
                                    }

                                    public void failed(FunctionError fe) {
                                        LOGGER.log(Level.FINE, "Failed to rest: " + fe);
                                    }
                                });
                            }

                            goSlowRun();
                        }
                    }
                }).start();

                activateAutonomy(true);
                if (SIMULATE_COMM_LOSS && COMM_LOSS_RANDOM.nextDouble() <= COMM_LOSS_PROB) {
                    LOGGER.fine("*** Dropping _server.startWaypoints");
                } else {
                    LOGGER.fine("*** Sending _server.startWaypoints");
                    LOGGER.info("BoatProxy [" + toString() + "] startWaypoints [" + _curWaypoints.toString() + "]");

                    // Make sure we don't send waypoint commands too fast - stop and go commands can get out of order otherwise
                    checkAndSleepForCmd();

                    _server.startWaypoints(_curWaypoints.toArray(new UtmPose[_curWaypoints.size()]), "POINT_AND_SHOOT", new FunctionObserver() {
                        public void completed(Object v) {
                            LOGGER.info("Successfully sent a waypoint in Go Slow: " + _curWaypoints.peek());
                        }

                        public void failed(FunctionError fe) {
                            // @todo Do something when start waypoints fails
                            LOGGER.warning("Start waypoints failed");
                        }
                    });
                }

            } catch (NoSuchElementException e) {
                LOGGER.info("Go slow is done! " + e);
                goSlowExecuting = false;
            }

        } else {

            LOGGER.info("Too soon to send next waypoint");

            (new Thread() {
                public void run() {
                    try {
                        sleep(goSlowRestTime / 2);
                        LOGGER.info("Thread awoke");
                    } catch (InterruptedException e) {
                    }
                    goSlowRun();
                }
            }).start();
        }
    }

    /**
     * Sends a twist velocity to the boat server to be executed
     *
     * @param t
     */
    public void setExternalVelocity(Twist t) {
        if (SIMULATE_COMM_LOSS && COMM_LOSS_RANDOM.nextDouble() <= COMM_LOSS_PROB) {
            LOGGER.fine("*** Dropping _server.setVelocity");
        } else {
            LOGGER.fine("*** Sending _server.setVelocity");
            _server.setVelocity(t, new FunctionObserver<Void>() {
                public void completed(Void v) {
                    LOGGER.log(Level.FINE, "Set velocity succeeded");
                }

                public void failed(FunctionError fe) {
                    LOGGER.severe("Set velocity failed!");
                }
            });
        }
    }

    public void activateAutonomy(final boolean activate) {

        if (SIMULATE_COMM_LOSS && COMM_LOSS_RANDOM.nextDouble() <= COMM_LOSS_PROB) {
            LOGGER.fine("*** Dropping _server.setAutonomous");
        } else {
            LOGGER.fine("*** Sending _server.setAutonomous");
            _server.setAutonomous(activate, new FunctionObserver<Void>() {

                @Override
                public void completed(Void v) {
                    LOGGER.log(Level.FINE, "Set autonomous to " + activate + " succeeded");
                    autonomyActive.set(true);
                }

                @Override
                public void failed(FunctionError fe) {
                    LOGGER.severe("Set autonomous to " + activate + " failed!");
                }
            });
        }
    }

    public void asyncGetWaypointStatus(FunctionObserver<WaypointState> fo) {

        if (SIMULATE_COMM_LOSS && COMM_LOSS_RANDOM.nextDouble() <= COMM_LOSS_PROB) {
            LOGGER.fine("*** Dropping _server.getWaypointStatus");
        } else {
            LOGGER.fine("*** Sending _server.getWaypointStatus");
            _server.getWaypointStatus(fo);
        }
    }

    public Color getColor() {
        return color;
    }

    public BufferedImage getLatestImg() {
        return latestImg;
    }

    public UdpVehicleServer getVehicleServer() {
        return _server;
    }

    @Override
    public String toString() {
        return name + "@" + _server.getVehicleService();
        // (masterURI == null ? "Unknown" : masterURI.toString());
    }

    private void checkAndSleepForCmd() {
        if (lastTime >= 0) {
            long timeGap = MIN_TIME_BTW_CMDS - (System.currentTimeMillis() - lastTime);
            if (timeGap > 0) {
                LOGGER.fine("Proxy [" + this + "] requires time gap before sending next command, sleeping for " + timeGap + "ms");
                try {
                    Thread.sleep(timeGap);
                } catch (InterruptedException ex) {
                    Logger.getLogger(BoatProxy.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
        lastTime = System.currentTimeMillis();
    }
}
