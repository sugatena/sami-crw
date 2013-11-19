package crw.handler;

import crw.Conversion;
import crw.Helper;
import crw.event.input.proxy.ProxyCreated;
import crw.event.input.proxy.ProxyPathCompleted;
import crw.event.input.proxy.ProxyPathFailed;
import crw.event.output.proxy.ConnectExistingProxy;
import crw.event.output.proxy.CreateSimulatedProxy;
import crw.event.output.proxy.ProxyExecutePath;
import crw.event.output.proxy.ProxyExecuteTask;
import crw.event.output.proxy.ProxyExploreArea;
import crw.event.output.proxy.ProxyStationKeep;
import crw.general.FastSimpleBoatSimulator;
import crw.proxy.BoatProxy;
import crw.ui.ImagePanel;
import edu.cmu.ri.crw.CrwNetworkUtils;
import edu.cmu.ri.crw.VehicleServer;
import edu.cmu.ri.crw.data.Utm;
import edu.cmu.ri.crw.data.UtmPose;
import edu.cmu.ri.crw.udp.UdpVehicleService;
import gov.nasa.worldwind.geom.Angle;
import gov.nasa.worldwind.geom.LatLon;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.render.Polygon;
import java.awt.Color;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import robotutils.Pose3D;
import sami.area.Area2D;
import sami.engine.Engine;
import sami.event.GeneratedEventListenerInt;
import sami.event.GeneratedInputEventSubscription;
import sami.event.InputEvent;
import sami.event.OutputEvent;
import sami.event.ProxyAbortMission;
import sami.handler.EventHandlerInt;
import sami.mission.Token;
import sami.path.Location;
import sami.path.Path;
import sami.path.PathUtm;
import sami.path.UTMCoordinate;
import sami.path.UTMCoordinate.Hemisphere;
import sami.proxy.ProxyInt;
import sami.proxy.ProxyListenerInt;
import sami.service.information.InformationServer;
import sami.service.information.InformationServiceProviderInt;

/**
 *
 * @author pscerri
 */
public class ProxyEventHandler implements EventHandlerInt, ProxyListenerInt, InformationServiceProviderInt {

    private static final Logger LOGGER = Logger.getLogger(ProxyEventHandler.class.getName());
    // For most of the interesting part of the planet, 1 degree latitude is something like 110,000m
    // Longtitude varies a bit more, but 90,000m is a decent number for the purpose of this calculation
    // See http://www.csgnetwork.com/degreelenllavcalc.html
    final double M_PER_LON_D = 1.0 / 90000.0;
    final double M_PER_LAT_D = 1.0 / 110000.0;
    // Sending a waypoints list of size > 68 causes failure due to data size
    final int MAX_SEGMENTS_PER_PROXY = 68;
    ArrayList<GeneratedEventListenerInt> listeners = new ArrayList<GeneratedEventListenerInt>();
    HashMap<GeneratedEventListenerInt, Integer> listenerGCCount = new HashMap<GeneratedEventListenerInt, Integer>();
    int portCounter = 0;

    public ProxyEventHandler() {
        LOGGER.log(Level.FINE, "Adding ProxyEventHandler as service provider");
        InformationServer.addServiceProvider(this);
    }

    @Override
    public void invoke(final OutputEvent oe, ArrayList<Token> tokens) {
        LOGGER.log(Level.FINE, "ProxyEventHandler invoked with " + oe);

        if (oe.getId() == null) {
            LOGGER.log(Level.WARNING, "\tOutputEvent " + oe + " has null event id");
        }
        if (oe.getMissionId() == null) {
            LOGGER.log(Level.WARNING, "\tOutputEvent " + oe + " has null mission id");
        }

        if (oe instanceof ProxyExecutePath) {
            int numProxies = 0;
            ArrayList<BoatProxy> tokenProxies = new ArrayList<BoatProxy>();
            for (Token token : tokens) {
                if (token.getProxy() != null && token.getProxy() instanceof BoatProxy) {
                    tokenProxies.add((BoatProxy) token.getProxy());
                    numProxies++;
                }
            }
            if (numProxies == 0) {
                LOGGER.log(Level.WARNING, "Place with ProxyExecutePath has no tokens with proxies attached: " + oe);
            } else if (numProxies > 1) {
                LOGGER.log(Level.WARNING, "Place with ProxyExecutePath has " + numProxies + " tokens with proxies attached: " + oe);
            }
            for (BoatProxy boatProxy : tokenProxies) {
                // Listen to the proxy
                boatProxy.addListener(this);
                //@todo we never stop listening
                // Send the path
                boatProxy.handleEvent(oe);
            }
        } else if (oe instanceof ProxyExecuteTask) {
            //@todo simulator integration
        } else if (oe instanceof ProxyExploreArea) {
            // Get the lawnmower path for the whole area
            // How many meters the proxy should move north after each horizontal section of the lawnmower pattern
            double latDegInc = M_PER_LAT_D * 10;
            ArrayList<Position> positions = new ArrayList<Position>();
            Area2D area = ((ProxyExploreArea) oe).getArea();
            for (Location location : area.getPoints()) {
                positions.add(Conversion.locationToPosition(location));
            }
            Polygon polygon = new Polygon(positions);
            Object[] tuple = getLawnmowerPath(polygon, latDegInc);
            ArrayList<Position> lawnmowerPositions = (ArrayList<Position>) tuple[0];
            double totalLength = (Double) tuple[1];

            // Divy up the waypoints to the selected proxies
            // Explore rectangle using horizontally oriented lawnmower paths
            int numProxies = 0;
            ArrayList<BoatProxy> tokenProxies = new ArrayList<BoatProxy>();
            for (Token token : tokens) {
                if (token.getProxy() != null && token.getProxy() instanceof BoatProxy) {
                    tokenProxies.add((BoatProxy) token.getProxy());
                    numProxies++;
                }
            }
            if (numProxies == 0) {
                LOGGER.log(Level.WARNING, "ProxyExecutePath had no relevant proxies attached: " + oe);
            }
            double lengthPerProxy = totalLength / numProxies, proxyLength, length;
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
                        if (lawnmowerIndex % 2 == 0) {
                            // Horizontal segment
                            length = Math.abs((p1.longitude.degrees - p2.longitude.degrees) * M_PER_LON_D);
                        } else {
                            // Vertical shift
                            length = latDegInc;
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
//                    LOGGER.info("i = " + i + " of " + (lawnmowerLocations.size() / MAX_SEGMENTS_PER_PROXY + 1) + ": sublist " + i * MAX_SEGMENTS_PER_PROXY + ", " + Math.min(lawnmowerLocations.size(), (i + 1) * MAX_SEGMENTS_PER_PROXY));
                        proxyLocations = lawnmowerLocations.subList(i * MAX_SEGMENTS_PER_PROXY, Math.min(lawnmowerLocations.size(), (i + 1) * MAX_SEGMENTS_PER_PROXY));
                        // Listen to the proxy
                        tokenProxies.get(proxyIndex).addListener(this);
                        //@todo we never stop listening
                        // Send the path
//                    LOGGER.log(Level.FINE, "Creating ProxyExecutePath with " + proxyLocations.size() + " waypoints for proxy " + tokenProxies.get(proxyIndex));
                        PathUtm path = new PathUtm(proxyLocations);
                        Hashtable<ProxyInt, Path> thisProxyPath = new Hashtable<ProxyInt, Path>();
                        thisProxyPath.put(tokenProxies.get(proxyIndex), path);
                        ProxyExecutePath proxyEvent = new ProxyExecutePath(oe.getId(), oe.getMissionId(), thisProxyPath);
                        tokenProxies.get(proxyIndex).handleEvent(proxyEvent);
                    }
                } else {
                    // We have finished assigning all the lawnmower path segments
                    // Send a blank path to the remaining proxies otherwise we won't get a ProxyPathComplete InputEvent                        
                    // Listen to the proxy
                    tokenProxies.get(proxyIndex).addListener(this);
                //@todo we never stop listening
                    // Send the path
                    Hashtable<ProxyInt, Path> thisProxyPath = new Hashtable<ProxyInt, Path>();
                    thisProxyPath.put(tokenProxies.get(proxyIndex), new PathUtm(new ArrayList<Location>()));
                    ProxyExecutePath proxyEvent = new ProxyExecutePath(oe.getId(), oe.getMissionId(), thisProxyPath);
                    tokenProxies.get(proxyIndex).handleEvent(proxyEvent);
                }
            }
        } else if (oe instanceof ProxyAbortMission) {
            for (Token token : tokens) {
                if (token.getProxy() != null) {
                    token.getProxy().abortMission(oe.getMissionId());
                }
            }
        } else if (oe instanceof ConnectExistingProxy) {
            // Connect to a non-simulated proxy
            ConnectExistingProxy connectEvent = (ConnectExistingProxy) oe;
            ProxyInt proxy = Engine.getInstance().getProxyServer().createProxy(connectEvent.name, connectEvent.color, CrwNetworkUtils.toInetSocketAddress(connectEvent.server));
            ImagePanel.setImagesDirectory(connectEvent.imageStorageDirectory);
            if (proxy != null) {
                ProxyCreated proxyCreated = new ProxyCreated(oe.getId(), oe.getMissionId(), proxy);
                for (GeneratedEventListenerInt listener : listeners) {
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
                UTMCoordinate utmc = createEvent.startLocation.getCoordinate();
                UtmPose p1 = new UtmPose(new Pose3D(utmc.getEasting(), utmc.getNorthing(), 0.0, 0.0, 0.0, 0.0), new Utm(utmc.getZoneNumber(), utmc.getHemisphere().equals(Hemisphere.NORTH)));
                server.setPose(p1);
                name = Helper.getUniqueName(name, proxyNames);
                proxyNames.add(name);
                ProxyInt proxy = Engine.getInstance().getProxyServer().createProxy(name, color, new InetSocketAddress("localhost", 11411 + portCounter));
                color = randomColor();
                portCounter++;

                if (proxy != null) {
                    relevantProxyList.add(proxy);
                } else {
                    LOGGER.severe("Failed to create simulated proxy");
                    error = true;
                }
            }
            if (!error) {
                ProxyCreated proxyCreated = new ProxyCreated(oe.getId(), oe.getMissionId(), relevantProxyList);
                for (GeneratedEventListenerInt listener : listeners) {
                    listener.eventGenerated(proxyCreated);
                }
            }
        } else if (oe instanceof ProxyStationKeep) {
            int numProxies = 0;
            ArrayList<BoatProxy> tokenProxies = new ArrayList<BoatProxy>();
            for (Token token : tokens) {
                if (token.getProxy() != null && token.getProxy() instanceof BoatProxy) {
                    tokenProxies.add((BoatProxy) token.getProxy());
                    numProxies++;
                }
            }
            if (numProxies == 0) {
                LOGGER.log(Level.WARNING, "Place with ProxyStationKeep has no tokens with proxies attached: " + oe);
            }
            for (BoatProxy boatProxy : tokenProxies) {
                // Listen to the proxy
                boatProxy.addListener(this);
                //@todo we never stop listening
                boatProxy.handleEvent(oe);
            }
        }
    }

    @Override
    public boolean offer(GeneratedInputEventSubscription sub) {
        LOGGER.log(Level.INFO, "ProxyEventHandler offered subscription: " + sub);
        if (sub.getSubscriptionClass() == ProxyPathCompleted.class
                || sub.getSubscriptionClass() == ProxyPathFailed.class
                || sub.getSubscriptionClass() == ProxyCreated.class) {
            LOGGER.log(Level.INFO, "\tProxyEventHandler taking subscription: " + sub);
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

    @Override
    public boolean cancel(GeneratedInputEventSubscription sub) {
        LOGGER.log(Level.FINE, "ProxyEventHandler asked to cancel subscription: " + sub);
        if ((sub.getSubscriptionClass() == ProxyPathCompleted.class
                || sub.getSubscriptionClass() == ProxyPathFailed.class
                || sub.getSubscriptionClass() == ProxyCreated.class)
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

    @Override
    public void eventOccurred(InputEvent proxyEventGenerated) {
        for (GeneratedEventListenerInt listener : listeners) {
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

    private Color randomColor() {
        Random rand = new Random();

        float r = rand.nextFloat();
        float g = rand.nextFloat();
        float b = rand.nextFloat();

        return new Color(r, g, b);
    }

    private Object[] getLawnmowerPath(Polygon area, double stepSize) {
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

        double totalLength = 0.0;
        Angle leftLon = null, rightLon = null;
        ArrayList<Position> path = new ArrayList<Position>();
        while (curLat.degrees <= maxLat.degrees) {
            // Left to right
            leftLon = getMinLonAt(area, minLon, maxLon, curLat);
            rightLon = getMaxLonAt(area, minLon, maxLon, curLat);
            if (leftLon != null && rightLon != null) {
                path.add(new Position(new LatLon(curLat, leftLon), 0.0));
                path.add(new Position(new LatLon(curLat, rightLon), 0.0));
                totalLength += Math.abs((rightLon.degrees - leftLon.degrees) * M_PER_LON_D);
            } else {
            }
            // Right to left
            curLat = curLat.addDegrees(stepSize);
            if (curLat.degrees <= maxLat.degrees) {
                totalLength += stepSize;
                rightLon = getMaxLonAt(area, minLon, maxLon, curLat);
                leftLon = getMinLonAt(area, minLon, maxLon, curLat);
                if (leftLon != null && rightLon != null) {
                    path.add(new Position(new LatLon(curLat, rightLon), 0.0));
                    path.add(new Position(new LatLon(curLat, leftLon), 0.0));
                    totalLength += Math.abs((rightLon.degrees - leftLon.degrees) * M_PER_LON_D);
                } else {
                }
            }
            curLat = curLat.addDegrees(stepSize);
            if (curLat.degrees <= maxLat.degrees) {
                totalLength += stepSize;
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
}
