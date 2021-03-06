package crw.handler;

import com.platypus.crw.CrwNetworkUtils;
import com.platypus.crw.FunctionObserver;
import com.platypus.crw.VehicleServer;
import com.platypus.crw.data.Utm;
import com.platypus.crw.data.UtmPose;
import com.platypus.crw.udp.UdpVehicleService;
import sami.Conversion;
import crw.CrwHelper;
import static crw.CrwHelper.LAT_D_PER_M;
import static crw.CrwHelper.LON_D_PER_M;
import static crw.CrwHelper.MAX_SEGMENTS_PER_PROXY;
import crw.event.input.proxy.ProxyCreated;
import crw.event.input.proxy.ProxyListCompleted;
import crw.event.input.proxy.ProxyPathCompleted;
import crw.event.input.proxy.ProxyPathFailed;
import crw.event.input.proxy.ProxyPoseUpdated;
import crw.event.input.proxy.SetGainsFailed;
import crw.event.input.proxy.SetGainsSucceeded;
import crw.event.input.service.AssembleLocationResponse;
import crw.event.input.service.DistributeLocationsResponse;
import crw.event.input.service.QuantityEqual;
import crw.event.input.service.QuantityGreater;
import crw.event.input.service.QuantityLess;
import crw.event.output.connectivity.DisconnectServer;
import crw.event.output.connectivity.ReconnectServer;
import crw.event.output.proxy.BlockMovement;
import crw.event.output.proxy.ConnectExistingProxy;
import crw.event.output.proxy.ConnectExistingProxyId;
import crw.event.output.proxy.CreateSimulatedProxy;
import crw.event.output.service.AssembleLocationRequest;
import crw.event.output.proxy.ProxyEmergencyAbort;
import crw.event.output.proxy.ProxyExecutePath;
import crw.event.output.proxy.ProxyExecutePathAndBlock;
import crw.event.output.proxy.ProxyExploreArea;
import crw.event.output.proxy.ProxyGotoListLocation;
import crw.event.output.proxy.ProxyGotoPoint;
import crw.event.output.proxy.ProxyGotoPointAndBlock;
import crw.event.output.proxy.ProxyResendWaypoints;
import crw.event.output.proxy.SetGains;
import crw.event.output.proxy.single.SingleProxyGotoLatLon;
import crw.event.output.service.DistributeLocationsRequest;
import crw.event.output.service.ProxyCompareDistanceRequest;
import crw.general.FastSimpleBoatSimulator;
import crw.proxy.BoatProxy;
import crw.proxy.clickthrough.ClickthroughProxy;
import crw.ui.ImagePanel;
import static crw.ui.teleop.GainsPanel.RUDDER_GAINS_AXIS;
import static crw.ui.teleop.GainsPanel.THRUST_GAINS_AXIS;
import gov.nasa.worldwind.geom.Angle;
import gov.nasa.worldwind.geom.LatLon;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.geom.coords.UTMCoord;
import gov.nasa.worldwind.render.Polygon;
import java.awt.Color;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import robotutils.Pose3D;
import sami.CoreHelper;
import sami.area.Area2D;
import sami.engine.Engine;
import sami.engine.PlanManager;
import sami.event.BlankInputEvent;
import sami.event.GeneratedEventListenerInt;
import sami.event.GeneratedInputEventSubscription;
import sami.event.InputEvent;
import sami.event.OutputEvent;
import sami.event.ProxyAbortMission;
import sami.event.ProxyAbortMissionReceived;
import sami.event.ProxyAddDescription;
import sami.handler.EventHandlerInt;
import sami.mission.Token;
import sami.path.Location;
import sami.path.Path;
import sami.path.PathUtm;
import sami.path.UTMCoordinate;
import sami.path.UTMCoordinate.Hemisphere;
import sami.proxy.ProxyInt;
import sami.proxy.ProxyListenerInt;
import sami.proxy.ProxyServerListenerInt;
import sami.service.information.InformationServer;
import sami.service.information.InformationServiceProviderInt;

/**
 *
 * @author pscerri
 */
public class ProxyEventHandler implements EventHandlerInt, ProxyListenerInt, InformationServiceProviderInt, ProxyServerListenerInt {

    private static final Logger LOGGER = Logger.getLogger(ProxyEventHandler.class.getName());
    final ArrayList<GeneratedEventListenerInt> listeners = new ArrayList<GeneratedEventListenerInt>();
    HashMap<GeneratedEventListenerInt, Integer> listenerGCCount = new HashMap<GeneratedEventListenerInt, Integer>();
    int portCounter = 0;
    private Hashtable<UUID, Integer> eventIdToAssembleCounter = new Hashtable<UUID, Integer>();

    public ProxyEventHandler() {
        LOGGER.log(Level.FINE, "Adding ProxyEventHandler as service provider");
        InformationServer.addServiceProvider(this);
        // Do not add as Proxy server listener here, will cause java.lang.ExceptionInInitializerError
        // Engine will add this for us
        //Engine.getInstance().getProxyServer().addListener(this);
    }

    @Override
    public void invoke(final OutputEvent oe, ArrayList<Token> tokens) {
        LOGGER.log(Level.FINE, "ProxyEventHandler invoked with OE [" + oe + "] and tokens [" + tokens + "]");
        if (oe.getId() == null) {
            LOGGER.log(Level.WARNING, "\tOutputEvent " + oe + " has null event id");
        }
        if (oe.getMissionId() == null) {
            LOGGER.log(Level.WARNING, "\tOutputEvent " + oe + " has null mission id");
        }

        if (oe instanceof ProxyGotoPoint) {
            int numProxies = 0;
            for (Token token : tokens) {
                if (token.getProxy() != null && (token.getProxy() instanceof BoatProxy || token.getProxy() instanceof ClickthroughProxy)) {
                    // Send the path
                    token.getProxy().handleEvent(oe, token.getTask());
                    numProxies++;
                }
            }
            if (numProxies == 0) {
                LOGGER.log(Level.WARNING, "Place with ProxyGotoPoint has no tokens with proxies attached: " + oe);
            }
        } else if (oe instanceof SingleProxyGotoLatLon) {
            int numProxies = 0;
            for (Token token : tokens) {
                if (token.getProxy() != null && (token.getProxy() instanceof BoatProxy || token.getProxy() instanceof ClickthroughProxy)) {
                    // Send the path
                    token.getProxy().handleEvent(oe, token.getTask());
                    numProxies++;
                }
            }
            if (numProxies == 0) {
                LOGGER.log(Level.WARNING, "Place with ProxyGotoPoint has no tokens with proxies attached: " + oe);
            } else if (numProxies > 1) {
                LOGGER.log(Level.WARNING, "Place with SingleProxyGotoLatLon had multiple tokens with proxies attached: " + oe);
            }
        } else if (oe instanceof ProxyGotoPointAndBlock) {
            int numProxies = 0;
            for (Token token : tokens) {
                if (token.getProxy() != null && (token.getProxy() instanceof BoatProxy || token.getProxy() instanceof ClickthroughProxy)) {
                    // Send the path
                    token.getProxy().handleEvent(oe, token.getTask());
                    numProxies++;
                }
            }
            if (numProxies == 0) {
                LOGGER.log(Level.WARNING, "Place with ProxyGotoPoint has no tokens with proxies attached: " + oe);
            }
        } else if (oe instanceof ProxyGotoListLocation) {
            // ProxyGotoListLocation has a list of locations for some set of proxies
            //  We use the number of generic tokens passed in as the "position" (starts at 1, not 0) in the list we want to access
            //  For each proxy in the token list, create a ProxyGotoPoint OE for the location at the corresponding position the list of locations for that proxy
            ProxyGotoListLocation pgll = (ProxyGotoListLocation) oe;

            int numGeneric = 0, numProxies = 0;
            for (Token token : tokens) {
                if (token.getType() == Token.TokenType.Generic) {
                    numGeneric++;
                }
                if (token.getProxy() != null && (token.getProxy() instanceof BoatProxy || token.getProxy() instanceof ClickthroughProxy)) {
                    numProxies++;
                }
            }
            if (numGeneric == 0) {
                LOGGER.log(Level.WARNING, "Place with ProxyGotoListLocation has no generic tokens to use as list position number: " + oe);
            } else if (numProxies == 0) {
                LOGGER.log(Level.WARNING, "Place with ProxyGotoListLocation has no tokens with proxies attached: " + oe);
            } else {
                for (Token token : tokens) {
                    if (token.getProxy() != null && (token.getProxy() instanceof BoatProxy || token.getProxy() instanceof ClickthroughProxy)) {
                        if (pgll.getProxyLocations().containsKey(token.getProxy())) {
                            ArrayList<Location> proxyLocations = pgll.getProxyLocations().get(token.getProxy());
                            if (numGeneric <= proxyLocations.size()) {
                                Location location = proxyLocations.get(numGeneric - 1);
                                Hashtable<ProxyInt, Location> thisProxyPoint = new Hashtable<ProxyInt, Location>();
                                thisProxyPoint.put(token.getProxy(), location);
                                ProxyGotoPoint proxyEvent = new ProxyGotoPoint(oe.getId(), oe.getMissionId(), thisProxyPoint);
                                // Return ProxyListCompleted for last waypoint in list, otherwise return ProxyPathCompleted
                                Class returnClass = proxyLocations.size() == numGeneric ? ProxyListCompleted.class : ProxyPathCompleted.class;
                                token.getProxy().handleEvent(proxyEvent, token.getTask(), returnClass);
                            } else {
                                LOGGER.warning("ProxyGotoListLocation list for proxy " + token.getProxy() + " has size " + proxyLocations.size() + ", but was requested a location at position " + numGeneric);
                            }
                        } else {
                            LOGGER.warning("ProxyGotoListLocation list has no entry for proxy " + token.getProxy());
                        }
                    }
                }
            }
        } else if (oe instanceof ProxyExploreArea) {
            // Get the lawnmower path for the whole area
            ArrayList<Position> positions = new ArrayList<Position>();
            Area2D area = ((ProxyExploreArea) oe).getArea();
            // How many meters the proxy should move north after each horizontal section of the lawnmower pattern
            for (Location location : area.getPoints()) {
                positions.add(Conversion.locationToPosition(location));
            }
            Polygon polygon = new Polygon(positions);
            double stepSizeM = ((ProxyExploreArea) oe).getSpacing();
            Object[] tuple = getLawnmowerPath(polygon, stepSizeM);
            ArrayList<Position> lawnmowerPositions = (ArrayList<Position>) tuple[0];
            double totalLength = (Double) tuple[1];
            
            // Divy up the waypoints to the selected proxies
            // Explore rectangle using horizontally oriented lawnmower paths
            int numProxies = 0;
            ArrayList<Token> tokensWithProxy = new ArrayList<Token>();
            for (Token token : tokens) {
                if (token.getProxy() != null && (token.getProxy() instanceof BoatProxy || token.getProxy() instanceof ClickthroughProxy)) {
                    tokensWithProxy.add(token);
                    numProxies++;
                }
            }
            if (numProxies == 0) {
                LOGGER.log(Level.WARNING, "ProxyExploreArea had no relevant proxies attached: " + oe);
            }
            double lengthPerProxy = totalLength / numProxies;
            double proxyLength, length;
            List<Location> lawnmowerLocations;
            int lawnmowerIndex = 0;
            for (int proxyIndex = 0; proxyIndex < numProxies; proxyIndex++) {
                lawnmowerLocations = new ArrayList<Location>();
                proxyLength = 0.0;
                // Must have at least two remaining positions to form a path segment
                Position p1 = null;
                if (lawnmowerIndex < lawnmowerPositions.size() - 1) {
                    // We still have lawnmower segments to assign
                    p1 = lawnmowerPositions.get(lawnmowerIndex);
                    lawnmowerIndex++;
                    boolean loop = lawnmowerIndex < lawnmowerPositions.size() && proxyLength < lengthPerProxy;
                    while (loop) {
                        Position p2 = lawnmowerPositions.get(lawnmowerIndex);
                        if (p1.latitude.degrees == p2.latitude.degrees) {
                            // Horizontal segment
                            length = Math.abs((p1.longitude.degrees - p2.longitude.degrees) / LON_D_PER_M);
                        } else {
                            // Vertical shift
                            length = stepSizeM;
                        }
                        if (proxyLength + length < lengthPerProxy) {
                            lawnmowerLocations.add(Conversion.positionToLocation(p2));
                            proxyLength += length;
                            p1 = p2;
                            lawnmowerIndex++;
                            loop = lawnmowerIndex < lawnmowerPositions.size() && proxyLength < lengthPerProxy;
                        } else {
                            loop = false;
                        }
                    }

                    if (lawnmowerLocations.size() > MAX_SEGMENTS_PER_PROXY) {
                        LOGGER.log(Level.WARNING, "Waypoint list size is " + lawnmowerLocations.size() + ": Breaking waypoints list into pieces to prevent communication failure");
                    }
                    List<Location> proxyLocations;
                    for (int i = 0; i < lawnmowerLocations.size() / MAX_SEGMENTS_PER_PROXY + 1; i++) {
//                        LOGGER.log(Level.FINE, "i = " + i + " of " + (lawnmowerLocations.size() / MAX_SEGMENTS_PER_PROXY + 1) + ": sublist " + i * MAX_SEGMENTS_PER_PROXY + ", " + Math.min(lawnmowerLocations.size(), (i + 1) * MAX_SEGMENTS_PER_PROXY));
                        proxyLocations = lawnmowerLocations.subList(i * MAX_SEGMENTS_PER_PROXY, Math.min(lawnmowerLocations.size(), (i + 1) * MAX_SEGMENTS_PER_PROXY));
                        // Send the path
//                        LOGGER.log(Level.FINE, "Creating ProxyExecutePath with " + proxyLocations.size() + " waypoints for proxy " + tokenProxies.get(proxyIndex));
                        if (proxyLocations.isEmpty()) {
                            LOGGER.warning("ExploreArea path for proxy " + tokensWithProxy.get(proxyIndex).getProxy().getProxyName() + " is empty");
                        }
                        // Create a separate ProxyGotoPoint OE for each waypoint so if execution is interrupted the entire path does not have to be repeated
                        for (int pointCount = 0; pointCount < proxyLocations.size(); pointCount++) {
                            Location location = proxyLocations.get(pointCount);
                            Hashtable<ProxyInt, Location> thisProxyPoint = new Hashtable<ProxyInt, Location>();
                            thisProxyPoint.put(tokensWithProxy.get(proxyIndex).getProxy(), location);
                            ProxyGotoPoint proxyEvent = new ProxyGotoPoint(oe.getId(), oe.getMissionId(), thisProxyPoint);
                            // Use blank return event unless last point
                            Class returnClass = pointCount < proxyLocations.size() - 1 ? BlankInputEvent.class : ProxyPathCompleted.class;
                            tokensWithProxy.get(proxyIndex).getProxy().handleEvent(proxyEvent, tokensWithProxy.get(proxyIndex).getTask(), returnClass);
                        }
                    }
                } else {
                    // We have finished assigning all the lawnmower path segments
                    // Send a blank path to the remaining proxies otherwise we won't get a ProxyPathComplete InputEvent                        
                    // Send the path
                    Hashtable<ProxyInt, Path> thisProxyPath = new Hashtable<ProxyInt, Path>();
                    thisProxyPath.put(tokensWithProxy.get(proxyIndex).getProxy(), new PathUtm(new ArrayList<Location>()));
                    ProxyExecutePath proxyEvent = new ProxyExecutePath(oe.getId(), oe.getMissionId(), thisProxyPath);
                    tokensWithProxy.get(proxyIndex).getProxy().handleEvent(proxyEvent, tokensWithProxy.get(proxyIndex).getTask());
                }
            }
        } else if (oe instanceof ProxyAddDescription) {
            int numProxies = 0;
            for (Token token : tokens) {
                if (token.getProxy() != null && (token.getProxy() instanceof BoatProxy)) {
                    ((BoatProxy) token.getProxy()).setNameModifier(((ProxyAddDescription) oe).description);
                    numProxies++;
                }
            }
            if (numProxies == 0) {
                LOGGER.log(Level.WARNING, "ProxyAddDescription had no relevant proxies attached: " + oe);
            }
        } else if (oe instanceof AssembleLocationRequest) {
            AssembleLocationRequest request = (AssembleLocationRequest) oe;
            int assembleCounter = 0;
            if (eventIdToAssembleCounter.contains(request.getId())) {
                assembleCounter = eventIdToAssembleCounter.get(request.getId());
            }

            int numProxies = 0;
            Hashtable<ProxyInt, Location> proxyPoints = new Hashtable<ProxyInt, Location>();
            ArrayList<ProxyInt> relevantProxies = new ArrayList<ProxyInt>();
            for (Token token : tokens) {
                if (token.getProxy() != null && (token.getProxy() instanceof BoatProxy || token.getProxy() instanceof ClickthroughProxy)) {
                    Location assembleLocation = getSpacedLocation(request.getLocation(), assembleCounter, request.getSpacing());
                    proxyPoints.put(token.getProxy(), assembleLocation);
                    relevantProxies.add(token.getProxy());
                    numProxies++;
                    assembleCounter++;
                }
            }
            if (numProxies == 0) {
                LOGGER.log(Level.WARNING, "Place with AssembleLocationRequest has no tokens with proxies attached: " + oe);
            }

            eventIdToAssembleCounter.put(request.getId(), assembleCounter);

            AssembleLocationResponse responseEvent = new AssembleLocationResponse(oe.getId(), oe.getMissionId(), proxyPoints, relevantProxies);
            ArrayList<GeneratedEventListenerInt> listenersCopy;
            synchronized (listeners) {
                listenersCopy = (ArrayList<GeneratedEventListenerInt>) listeners.clone();
            }
            for (GeneratedEventListenerInt listener : listenersCopy) {
                LOGGER.log(Level.FINE, "\tSending response to listener: " + listener);
                listener.eventGenerated(responseEvent);
            }
        } else if (oe instanceof DistributeLocationsRequest) {
            // Distribute the event's list of locations amongst the proxies in the received token list
            DistributeLocationsRequest request = (DistributeLocationsRequest) oe;

            int numProxies = 0;
            for (Token token : tokens) {
                if (token.getProxy() != null && (token.getProxy() instanceof BoatProxy || token.getProxy() instanceof ClickthroughProxy)) {
                    numProxies++;
                }
            }
            // Truncated # locations per proxy
            int locationsPerProxy;
            if (numProxies == 0) {
                LOGGER.log(Level.WARNING, "Place with DistributeLocationsRequest has no tokens with proxies attached: " + oe);
                // Still publish a DistributeLocationsResponse so the plan doesn't freeze
                //  Avoid divide by zero error
                locationsPerProxy = 0;
            } else {
                // Truncated # locations per proxy
                locationsPerProxy = (int) (request.getLocations().getPoints().size() / numProxies);
            }
            
            // Give each proxy [locationsPerProxy] locations and give any remaining locations to the last proxy
            // ex: 5 locations, 2 proxies: 2 to p1, 3 to p2
            // ex: 2 location, 3 proxies: 0 to p1, 0 to p2, 2 to p3
            int locationIndex = 0;
            int proxyCounter = 0;
            Hashtable<ProxyInt, ArrayList<Location>> proxyPoints = new Hashtable<ProxyInt, ArrayList<Location>>();
            ArrayList<ProxyInt> relevantProxies = new ArrayList<ProxyInt>();
            for (Token token : tokens) {
                if (token.getProxy() != null && (token.getProxy() instanceof BoatProxy || token.getProxy() instanceof ClickthroughProxy)) {
                    // Assign the next (locationsPerProxy) locations from the event's location list to this proxy
                    ArrayList<Location> locations = new ArrayList<Location>();
                    for (int i = 0; i < locationsPerProxy; i++) {
                        locations.add(request.getLocations().getPoints().get(i + locationIndex));
                    }
                    locationIndex += locationsPerProxy;

                    // Put any remaining locations (if it did not divide perfectly) into the last proxy's list
                    if (proxyCounter == numProxies - 1) {
                        while (locationIndex < request.getLocations().getPoints().size()) {
                            locations.add(request.getLocations().getPoints().get(locationIndex));
                            locationIndex++;
                        }
                    }
                    proxyPoints.put(token.getProxy(), locations);
                    relevantProxies.add(token.getProxy());
                    proxyCounter++;
                }
            }

            DistributeLocationsResponse responseEvent = new DistributeLocationsResponse(oe.getId(), oe.getMissionId(), proxyPoints, relevantProxies);
            ArrayList<GeneratedEventListenerInt> listenersCopy;
            synchronized (listeners) {
                listenersCopy = (ArrayList<GeneratedEventListenerInt>) listeners.clone();
            }
            for (GeneratedEventListenerInt listener : listenersCopy) {
                LOGGER.log(Level.FINE, "\tSending response to listener: " + listener);
                listener.eventGenerated(responseEvent);
            }
        } else if (oe instanceof ProxyCompareDistanceRequest) {
            ProxyCompareDistanceRequest request = (ProxyCompareDistanceRequest) oe;
            ArrayList<InputEvent> responses = new ArrayList<InputEvent>();

            int numProxies = 0;
            ArrayList<ProxyInt> relevantProxies;
            for (Token token : tokens) {
//                if (token.getProxy() != null && token.getProxy() instanceof BoatProxy) {
//                    BoatProxy boatProxy = (BoatProxy) token.getProxy();
                if (token.getProxy() != null && (token.getProxy() instanceof BoatProxy || token.getProxy() instanceof ClickthroughProxy)) {

                    ProxyInt proxy = token.getProxy();
                    Position proxyPosition = null;
                    if (proxy instanceof BoatProxy) {
                        proxyPosition = ((BoatProxy) proxy).getPosition();
                    } else if (proxy instanceof ClickthroughProxy) {
                        proxyPosition = ((ClickthroughProxy) proxy).getPosition();
                    }

                    if (!request.getProxyCompareLocation().containsKey(proxy)) {
                        LOGGER.severe("Passed in proxy token for " + proxy + " to place with ProxyCompareDistanceRequest, but there is no compare location entry for the proxy!");
                    } else {
//                        try {
                            Position stationKeepPosition = Conversion.locationToPosition(request.getProxyCompareLocation().get(proxy));
                            UTMCoord stationKeepUtm = UTMCoord.fromLatLon(stationKeepPosition.latitude, stationKeepPosition.longitude);
                            UtmPose stationKeepPose = new UtmPose(new Pose3D(stationKeepUtm.getEasting(), stationKeepUtm.getNorthing(), 0.0, 0.0, 0.0, 0.0), new Utm(stationKeepUtm.getZone(), stationKeepUtm.getHemisphere().contains("North")));
                            UTMCoord boatUtm = UTMCoord.fromLatLon(proxyPosition.latitude, proxyPosition.longitude);
                            UtmPose boatPose = new UtmPose(new Pose3D(boatUtm.getEasting(), boatUtm.getNorthing(), 0.0, 0.0, 0.0, 0.0), new Utm(boatUtm.getZone(), boatUtm.getHemisphere().contains("North")));
                            double distance = boatPose.pose.getEuclideanDistance(stationKeepPose.pose);

                            InputEvent response;
                            relevantProxies = new ArrayList<ProxyInt>();
                            relevantProxies.add(proxy);
                            if (distance > request.compareDistance) {
                                response = new QuantityGreater(request.getId(), request.getMissionId(), relevantProxies);
                            } else if (distance < request.compareDistance) {
                                response = new QuantityLess(request.getId(), request.getMissionId(), relevantProxies);
                            } else {
                                response = new QuantityEqual(request.getId(), request.getMissionId(), relevantProxies);
                            }
                            responses.add(response);
//                        } catch (NullPointerException npe) {
//                            LOGGER.severe("Caught NPE in  v, assuming greater");
//                            npe.printStackTrace();
//                            relevantProxies = new ArrayList<ProxyInt>();
//                            relevantProxies.add(proxy);
//                            InputEvent response = new QuantityGreater(request.getId(), request.getMissionId(), relevantProxies);
//                            responses.add(response);
//                        }
                    }
                    numProxies++;
                }
            }
            if (numProxies == 0) {
                LOGGER.log(Level.WARNING, "Place with ProxyCompareDistanceRequest has no tokens with proxies attached: " + oe + ", tokens [" + tokens + "]");
            }

            ArrayList<GeneratedEventListenerInt> listenersCopy;
            synchronized (listeners) {
                listenersCopy = (ArrayList<GeneratedEventListenerInt>) listeners.clone();
            }
            for (GeneratedEventListenerInt listener : listenersCopy) {
                for (InputEvent response : responses) {
                    LOGGER.log(Level.FINE, "\tSending response: " + response + " to listener: " + listener);
                    listener.eventGenerated(response);
                }
            }
        } else if (oe instanceof ProxyAbortMission) {
            for (Token token : tokens) {
                if (token.getProxy() != null) {
                    token.getProxy().abortMission(oe.getMissionId());
                    ArrayList<ProxyInt> relevantProxies = new ArrayList<ProxyInt>();
                    relevantProxies.add(token.getProxy());

                    // Now abort any sub-missions
                    //  Only do immediate children, as this will recurse
                    ArrayList<PlanManager> subPMs = Engine.getInstance().getSubPlanManagers(oe.getMissionId());
                    for (PlanManager subPm : subPMs) {
                        // Check that sub PM has an active AbortMissionReceived that will handle this
                        //  Shared SMs may not currently have any active places, so the IE will not trigger an transition
                        //  Non-shared SMs should have an active place which will trigger a transition, but check just in case
                        boolean found = false;
                        ArrayList<InputEvent> smActiveInputEvents = subPm.getActiveInputEventsClone();
                        for (InputEvent ie : smActiveInputEvents) {
                            if (ie instanceof ProxyAbortMissionReceived) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            // No active AbortMissionReceived in the SM
                            if (!subPm.getIsSharedSm()) {
                                LOGGER.severe("Want to send ProxyAbortMissionReceived to non-shared SM, but it has no active IE, manually aborting proxy from SM");
                            }
                            // Abort this PM manually
                            //@todo this only goes one layer deep - what if SM has SM also without ProxyAbortMissionReceived?
                            token.getProxy().abortMission(subPm.missionId);
                        } else {
                            subPm.eventGenerated(new ProxyAbortMissionReceived(subPm.missionId, relevantProxies));
                        }
                    }
                }
            }
        } else if (oe instanceof ConnectExistingProxy) {
            // Connect to a non-simulated proxy
            ConnectExistingProxy connectEvent = (ConnectExistingProxy) oe;
            ProxyInt proxy = Engine.getInstance().getProxyServer().createProxy(connectEvent.name, connectEvent.color, CrwNetworkUtils.toInetSocketAddress(connectEvent.server));
            ImagePanel.setImagesDirectory(connectEvent.imageStorageDirectory);
            if (proxy != null) {
                // Don't specify any matching criteria so this goes to all plans
                //ProxyCreated proxyCreated = new ProxyCreated(oe.getId(), oe.getMissionId(), proxy);
                ProxyCreated proxyCreated = new ProxyCreated(null, null, proxy);
                ArrayList<GeneratedEventListenerInt> listenersCopy;
                synchronized (listeners) {
                    listenersCopy = (ArrayList<GeneratedEventListenerInt>) listeners.clone();
                }
                for (GeneratedEventListenerInt listener : listenersCopy) {
                    listener.eventGenerated(proxyCreated);
                }
            } else {
                LOGGER.severe("Failed to connect proxy");
            }
        } else if (oe instanceof ConnectExistingProxyId) {
            // Connect to a non-simulated proxy
            ConnectExistingProxyId connectEvent = (ConnectExistingProxyId) oe;
            ProxyInt proxy = Engine.getInstance().getProxyServer().createProxy(connectEvent.boatProxyId.name, connectEvent.boatProxyId.color, CrwNetworkUtils.toInetSocketAddress(connectEvent.boatProxyId.server));
            ImagePanel.setImagesDirectory(connectEvent.boatProxyId.imageStorageDirectory);
            if (proxy != null) {
                // Don't specify any matching criteria so this goes to all plans
                //ProxyCreated proxyCreated = new ProxyCreated(oe.getId(), oe.getMissionId(), proxy);
                ProxyCreated proxyCreated = new ProxyCreated(null, null, proxy);
                ArrayList<GeneratedEventListenerInt> listenersCopy;
                synchronized (listeners) {
                    listenersCopy = (ArrayList<GeneratedEventListenerInt>) listeners.clone();
                }
                for (GeneratedEventListenerInt listener : listenersCopy) {
                    listener.eventGenerated(proxyCreated);
                }
            } else {
                LOGGER.severe("Failed to connect proxy");
            }
        } else if (oe instanceof CreateSimulatedProxy) {
            CreateSimulatedProxy createEvent = (CreateSimulatedProxy) oe;
            String name = createEvent.name;
            Color color = createEvent.color;
            boolean error = false;
            ArrayList<ProxyInt> relevantProxyList = new ArrayList<ProxyInt>();
            ArrayList<String> proxyNames = new ArrayList<String>();
            ArrayList<ProxyInt> proxyList = Engine.getInstance().getProxyServer().getProxyListClone();
            for (ProxyInt proxy : proxyList) {
                proxyNames.add(proxy.getProxyName());
            }
            for (int i = 0; i < createEvent.numberToCreate; i++) {
                // Create a simulated boat and run a ROS server around it
                VehicleServer server = new FastSimpleBoatSimulator();
                UdpVehicleService rosServer = new UdpVehicleService(11411 + portCounter, server);
                // Space out multiple simulated boats by 1m
                Location spacedLocation = getSpacedLocation(createEvent.startLocation, i, 1);
                UTMCoordinate utmc = spacedLocation.getCoordinate();
                UtmPose p1 = new UtmPose(new Pose3D(utmc.getEasting(), utmc.getNorthing(), 0.0, 0.0, 0.0, 0.0), new Utm(utmc.getZoneNumber(), utmc.getHemisphere().equals(Hemisphere.NORTH)));
                server.setPose(p1);
                name = CoreHelper.getUniqueName(name, proxyNames);
                proxyNames.add(name);
                ProxyInt proxy = Engine.getInstance().getProxyServer().createProxy(name, color, new InetSocketAddress("localhost", 11411 + portCounter));
                color = CrwHelper.randomColor();
                portCounter++;

                if (proxy != null) {
                    relevantProxyList.add(proxy);
                } else {
                    LOGGER.severe("Failed to create simulated proxy");
                    error = true;
                }
            }
            // Sleep to give time for processes to start
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                Logger.getLogger(ProxyEventHandler.class.getName()).log(Level.SEVERE, null, ex);
            }
            // After sleep, generated ProxyCreated event
            if (!error) {
                // Don't specify any matching criteria so this goes to all plans
                //ProxyCreated proxyCreated = new ProxyCreated(oe.getId(), oe.getMissionId(), relevantProxyList);
                ProxyCreated proxyCreated = new ProxyCreated(null, null, relevantProxyList);
                ArrayList<GeneratedEventListenerInt> listenersCopy;
                synchronized (listeners) {
                    listenersCopy = (ArrayList<GeneratedEventListenerInt>) listeners.clone();
                }
                for (GeneratedEventListenerInt listener : listenersCopy) {
                    listener.eventGenerated(proxyCreated);
                }
            }
        } else if (oe instanceof SetGains) {
            final SetGains setGains = (SetGains) oe;
            ArrayList<InputEvent> responses = new ArrayList<InputEvent>();

            int numBoatProxies = 0;
            // @todo Grouped or individual GainsSent result?
            for (Token token : tokens) {
                if (token.getProxy() != null && token.getProxy() instanceof BoatProxy) {
                    numBoatProxies++;

                    BoatProxy boatProxy = (BoatProxy) token.getProxy();
                    boatProxy.getVehicleServer().setGains(THRUST_GAINS_AXIS, new double[]{setGains.thrustP, setGains.thrustI, setGains.thrustD}, new FunctionObserver<Void>() {
                        public void completed(Void v) {
                            LOGGER.fine("Set thrust gains succeeded: Axis [" + THRUST_GAINS_AXIS + "] PID [" + setGains.thrustP + ", " + setGains.thrustI + ", " + setGains.thrustD + "]");
                        }

                        public void failed(FunctionObserver.FunctionError fe) {
                            LOGGER.severe("Set thrust gains failed: Axis [" + THRUST_GAINS_AXIS + "] PID [" + setGains.thrustP + ", " + setGains.thrustI + ", " + setGains.thrustD + "]");
                        }
                    });
                    boatProxy.getVehicleServer().setGains(RUDDER_GAINS_AXIS, new double[]{setGains.rudderP, setGains.rudderI, setGains.rudderD}, new FunctionObserver<Void>() {
                        public void completed(Void v) {
                            LOGGER.fine("Set rudder gains succeeded: Axis [" + RUDDER_GAINS_AXIS + "] PID [" + setGains.rudderP + ", " + setGains.rudderI + ", " + setGains.rudderD + "]");
                        }

                        public void failed(FunctionObserver.FunctionError fe) {
                            LOGGER.severe("Set rudder gains failed: Axis [" + RUDDER_GAINS_AXIS + "] PID [" + setGains.rudderP + ", " + setGains.rudderI + ", " + setGains.rudderD + "]");
                        }
                    });

                    //@todo add in recognition of async success or failure
                    // SetGainsSucceeded
                    // SetGainsFailed
                }
            }
            if (numBoatProxies == 0) {
                LOGGER.log(Level.WARNING, "Place with SetGains has no tokens with boat proxies attached: " + oe + ", tokens [" + tokens + "]");
            }

            ArrayList<GeneratedEventListenerInt> listenersCopy;
            synchronized (listeners) {
                listenersCopy = (ArrayList<GeneratedEventListenerInt>) listeners.clone();
            }
            for (GeneratedEventListenerInt listener : listenersCopy) {
                for (InputEvent response : responses) {
                    LOGGER.log(Level.FINE, "\tSending response: " + response + " to listener: " + listener);
                    listener.eventGenerated(response);
                }
            }
        } else if (oe instanceof BlockMovement) {
            int numProxies = 0;
            for (Token token : tokens) {
                if (token.getProxy() != null && (token.getProxy() instanceof BoatProxy || token.getProxy() instanceof ClickthroughProxy)) {
                    // Send the path
                    token.getProxy().handleEvent(oe, token.getTask());
                    numProxies++;
                }
            }
            if (numProxies == 0) {
                LOGGER.log(Level.WARNING, "Place with ProxyBlockMovement has no tokens with proxies attached: " + oe);
            }
        } else if (oe instanceof ProxyExecutePath
                || oe instanceof ProxyEmergencyAbort
                || oe instanceof ProxyResendWaypoints
                || oe instanceof ProxyExecutePathAndBlock
                || oe instanceof DisconnectServer
                || oe instanceof ReconnectServer) {
            int numProxies = 0;
            for (Token token : tokens) {
                if (token.getProxy() != null && (token.getProxy() instanceof BoatProxy || token.getProxy() instanceof ClickthroughProxy)) {
                    token.getProxy().handleEvent(oe, token.getTask());
                    numProxies++;
                }
            }
            if (numProxies == 0) {
                LOGGER.log(Level.WARNING, "Place with ProxyEventHandler OE has no tokens with proxies attached: " + oe);
            }
        } else {
            LOGGER.warning("ProxyEventHandler has no handling for event " + oe);
        }
    }

    @Override
    public boolean offer(GeneratedInputEventSubscription sub) {
        LOGGER.log(Level.FINE, "ProxyEventHandler offered subscription: " + sub);
        synchronized (listeners) {
            if (sub.getSubscriptionClass() == ProxyPathCompleted.class
                    || sub.getSubscriptionClass() == ProxyListCompleted.class
                    || sub.getSubscriptionClass() == ProxyPathFailed.class
                    || sub.getSubscriptionClass() == ProxyCreated.class
                    || sub.getSubscriptionClass() == AssembleLocationResponse.class
                    || sub.getSubscriptionClass() == DistributeLocationsResponse.class
                    || sub.getSubscriptionClass() == QuantityGreater.class
                    || sub.getSubscriptionClass() == QuantityLess.class
                    || sub.getSubscriptionClass() == QuantityEqual.class
                    || sub.getSubscriptionClass() == ProxyPoseUpdated.class
                    || sub.getSubscriptionClass() == SetGainsFailed.class
                    || sub.getSubscriptionClass() == SetGainsSucceeded.class) {
                LOGGER.log(Level.FINE, "\tProxyEventHandler taking subscription: " + sub);
                if (!listeners.contains(sub.getListener())) {
                    LOGGER.log(Level.FINE, "\t\tProxyEventHandler adding listener: " + sub.getListener());
                    listeners.add(sub.getListener());
                    listenerGCCount.put(sub.getListener(), 1);
                } else {
                    LOGGER.log(Level.FINE, "\t\tProxyEventHandler incrementing listener: " + sub.getListener());
                    listenerGCCount.put(sub.getListener(), listenerGCCount.get(sub.getListener()) + 1);
                }
                return true;
            }
            return false;
        }
    }

    @Override
    public boolean cancel(GeneratedInputEventSubscription sub) {
        LOGGER.log(Level.FINE, "ProxyEventHandler asked to cancel subscription: " + sub);
        synchronized (listeners) {
            if ((sub.getSubscriptionClass() == ProxyPathCompleted.class
                    || sub.getSubscriptionClass() == ProxyListCompleted.class
                    || sub.getSubscriptionClass() == ProxyPathFailed.class
                    || sub.getSubscriptionClass() == ProxyCreated.class
                    || sub.getSubscriptionClass() == AssembleLocationResponse.class
                    || sub.getSubscriptionClass() == DistributeLocationsResponse.class
                    || sub.getSubscriptionClass() == QuantityGreater.class
                    || sub.getSubscriptionClass() == QuantityLess.class
                    || sub.getSubscriptionClass() == QuantityEqual.class
                    || sub.getSubscriptionClass() == ProxyPoseUpdated.class
                    || sub.getSubscriptionClass() == SetGainsFailed.class
                    || sub.getSubscriptionClass() == SetGainsSucceeded.class)
                    && listeners.contains(sub.getListener())) {
                LOGGER.log(Level.FINE, "\tProxyEventHandler canceling subscription: " + sub);
                if (listenerGCCount.get(sub.getListener()) == 1) {
                    // Remove listener
                    LOGGER.log(Level.FINE, "\t\tProxyEventHandler removing listener: " + sub.getListener());
                    listeners.remove(sub.getListener());
                    listenerGCCount.remove(sub.getListener());
                } else {
                    // Decrement garbage colleciton count
                    LOGGER.log(Level.FINE, "\t\tProxyEventHandler decrementing listener: " + sub.getListener());
                    listenerGCCount.put(sub.getListener(), listenerGCCount.get(sub.getListener()) - 1);
                }
                return true;
            }
            return false;
        }
    }

    @Override
    public void eventOccurred(InputEvent proxyEventGenerated) {
        ArrayList<GeneratedEventListenerInt> listenersCopy;
        synchronized (listeners) {
            listenersCopy = (ArrayList<GeneratedEventListenerInt>) listeners.clone();
        }
        LOGGER.log(Level.FINE, "Event occurred: " + proxyEventGenerated + ", rp: " + proxyEventGenerated.getRelevantProxyList() + ", listeners: " + listenersCopy);
        for (GeneratedEventListenerInt listener : listenersCopy) {
            listener.eventGenerated(proxyEventGenerated);
        }
    }

    @Override
    public void poseUpdated() {
//        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void waypointsUpdated() {
//        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void waypointsComplete() {
//        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void proxyAdded(ProxyInt p) {
        p.addListener(this);
    }

    @Override
    public void proxyRemoved(ProxyInt p) {
    }

    private Object[] getLawnmowerPath(Polygon area, double stepSizeM) {
        // Compute the bounding box
        Angle minLat = Angle.POS360;
        Angle maxLat = Angle.NEG360;
        Angle minLon = Angle.POS360;
        Angle maxLon = Angle.NEG360;
        Angle curLat = null;
        for (LatLon latLon : area.getOuterBoundary()) {
            if (latLon.latitude.degrees > maxLat.degrees) {
                maxLat = latLon.latitude;
            } else if (latLon.latitude.degrees < minLat.degrees) {
                minLat = latLon.latitude;
            }
            if (latLon.longitude.degrees > maxLon.degrees) {
                maxLon = latLon.longitude;
            } else if (latLon.longitude.degrees < minLon.degrees) {
                minLon = latLon.longitude;
            }
        }
        curLat = minLat;
        
        double stepSizeDeg1 = stepSizeM * LAT_D_PER_M;
        UTMCoord utm1 = UTMCoord.fromLatLon(minLat, minLon);
        UTMCoord utm2 = UTMCoord.fromUTM(utm1.getZone(), utm1.getHemisphere(), utm1.getEasting(), utm1.getNorthing() + stepSizeM);
        double stepSizeDeg2 = Math.abs(utm1.getLatitude().degrees - utm2.getLatitude().degrees);

        double totalLength = 0.0;
        Angle leftLon = null, rightLon = null;
        ArrayList<Position> path = new ArrayList<Position>();
        while (curLat.degrees <= maxLat.degrees) {
            // Left to right
            leftLon = getMinLonAt(area, minLon, maxLon, curLat);
            rightLon = getMaxLonAt(area, minLon, maxLon, curLat);
            if (leftLon != null && rightLon != null) {
                Position pLeft = new Position(new LatLon(curLat, leftLon), 0.0);
                Position pRight = new Position(new LatLon(curLat, rightLon), 0.0);
                path.add(pLeft);
                path.add(pRight);
                UTMCoord utmLeft = UTMCoord.fromLatLon(curLat, leftLon);
                UTMCoord utmRight = UTMCoord.fromLatLon(curLat, rightLon);
                double d1 = Math.abs((rightLon.degrees - leftLon.degrees) / LON_D_PER_M);
                //@todo assumes same zone
                double d2 = Math.abs(utmRight.getEasting() - utmLeft.getEasting());
                totalLength += d1;
            } else {
            }
            // Right to left
            curLat = curLat.addDegrees(stepSizeDeg1);
            if (curLat.degrees <= maxLat.degrees) {
                totalLength += stepSizeDeg1;
                rightLon = getMaxLonAt(area, minLon, maxLon, curLat);
                leftLon = getMinLonAt(area, minLon, maxLon, curLat);
                if (leftLon != null && rightLon != null) {
                    Position pRight = new Position(new LatLon(curLat, rightLon), 0.0);
                    Position pLeft = new Position(new LatLon(curLat, leftLon), 0.0);
                    path.add(pRight);
                    path.add(pLeft);
                    UTMCoord utmRight = UTMCoord.fromLatLon(curLat, rightLon);
                    UTMCoord utmLeft = UTMCoord.fromLatLon(curLat, leftLon);
                    double d1 = Math.abs((rightLon.degrees - leftLon.degrees) / LON_D_PER_M);
                    //@todo assumes same zone
                    double d2 = Math.abs(utmRight.getEasting() - utmLeft.getEasting());
                    totalLength += d1;
                } else {
                }
            }
            curLat = curLat.addDegrees(stepSizeDeg1);
            if (curLat.degrees <= maxLat.degrees) {
                totalLength += stepSizeM;
            }
        }

        return new Object[]{path, totalLength};
    }

    private static Angle getMinLonAt(Polygon area, Angle minLon, Angle maxLon, Angle lat) {
        final double lonDiff = 1.0 / 90000.0 * 10.0;
        LatLon latLon = new LatLon(lat, minLon);
        while (!isLocationInside(latLon, (ArrayList<LatLon>) area.getOuterBoundary()) && minLon.degrees <= maxLon.degrees) {
            minLon = minLon.addDegrees(lonDiff);
            latLon = new LatLon(lat, minLon);
            if (minLon.degrees > maxLon.degrees) {
                // Overshot (this part of the area is tiny), so ignore it by returning null
                return null;
            }
        }
        return minLon;
    }

    private static Angle getMaxLonAt(Polygon area, Angle minLon, Angle maxLon, Angle lat) {
        final double lonDiff = 1.0 / 90000.0 * 10.0;
        LatLon latLon = new LatLon(lat, maxLon);
        while (!isLocationInside(latLon, (ArrayList<LatLon>) area.getOuterBoundary())) {
            maxLon = maxLon.addDegrees(-lonDiff);
            latLon = new LatLon(lat, maxLon);
            if (maxLon.degrees < minLon.degrees) {
                // Overshot (this part of the area is tiny), so ignore it by returning null
                return null;
            }
        }
        return maxLon;
    }

    /**
     * From: http://forum.worldwindcentral.com/showthread.php?t=20739
     *
     * @param point
     * @param positions
     * @return
     */
    public static boolean isLocationInside(LatLon point, ArrayList<? extends LatLon> positions) {
        boolean result = false;
        LatLon p1 = positions.get(0);
        for (int i = 1; i < positions.size(); i++) {
            LatLon p2 = positions.get(i);

            if (((p2.getLatitude().degrees <= point.getLatitude().degrees
                    && point.getLatitude().degrees < p1.getLatitude().degrees)
                    || (p1.getLatitude().degrees <= point.getLatitude().degrees
                    && point.getLatitude().degrees < p2.getLatitude().degrees))
                    && (point.getLongitude().degrees < (p1.getLongitude().degrees - p2.getLongitude().degrees)
                    * (point.getLatitude().degrees - p2.getLatitude().degrees)
                    / (p1.getLatitude().degrees - p2.getLatitude().degrees) + p2.getLongitude().degrees)) {
                result = !result;
            }
            p1 = p2;
        }
        return result;
    }

    private Location getSpacedLocation(Location centerLocation, int assembleCounter, double spacing) {
        Location spacedLocation;
        if (assembleCounter == 0) {
            spacedLocation = centerLocation;
        } else {
            int direction = (assembleCounter - 1) % 8;
            int magnitude = (assembleCounter - 1) / 8 + 1;
            UTMCoordinate centerCoord = centerLocation.getCoordinate();
            UTMCoordinate proxyCoord = new UTMCoordinate(centerCoord.getEasting(), centerCoord.getNorthing(), centerCoord.getZone());
            switch (direction) {
                case 0:
                    //  0: N
                    proxyCoord.setNorthing(centerCoord.getNorthing() + magnitude * spacing);
                    break;
                case 1:
                    //  1: NE
                    proxyCoord.setNorthing(centerCoord.getNorthing() + magnitude * spacing);
                    proxyCoord.setEasting(centerCoord.getEasting() + magnitude * spacing);
                    break;
                case 2:
                    //  2: E
                    proxyCoord.setEasting(centerCoord.getEasting() + magnitude * spacing);
                    break;
                case 3:
                    //  3: SE
                    proxyCoord.setNorthing(centerCoord.getNorthing() - magnitude * spacing);
                    proxyCoord.setEasting(centerCoord.getEasting() + magnitude * spacing);
                    break;
                case 4:
                    //  4: S
                    proxyCoord.setNorthing(centerCoord.getNorthing() - magnitude * spacing);
                    break;
                case 5:
                    //  5: SW
                    proxyCoord.setNorthing(centerCoord.getNorthing() - magnitude * spacing);
                    proxyCoord.setEasting(centerCoord.getEasting() - magnitude * spacing);
                    break;
                case 6:
                    //  6: W
                    proxyCoord.setEasting(centerCoord.getEasting() - magnitude * spacing);
                    break;
                case 7:
                    //  7: NW
                    proxyCoord.setNorthing(centerCoord.getNorthing() + magnitude * spacing);
                    proxyCoord.setEasting(centerCoord.getEasting() - magnitude * spacing);
                    break;
            }
            spacedLocation = new Location(proxyCoord, centerLocation.getAltitude());
        }
        return spacedLocation;
    }
}
