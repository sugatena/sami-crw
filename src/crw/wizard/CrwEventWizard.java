package crw.wizard;

import crw.event.input.operator.OperatorAcceptsAllocation;
import crw.event.input.operator.OperatorCreatedArea;
import crw.event.input.operator.OperatorRejectsAllocation;
import crw.event.input.operator.OperatorSelectsBoat;
import crw.event.input.operator.OperatorSelectsBoatList;
import crw.event.input.proxy.ProxyCreated;
import crw.event.input.proxy.ProxyPathCompleted;
import crw.event.input.service.AllocationResponse;
import crw.event.output.operator.OperatorCreateArea;
import crw.event.output.operator.OperatorSelectBoat;
import crw.event.output.operator.OperatorSelectBoatList;
import crw.event.output.proxy.ConnectExistingProxy;
import crw.event.output.proxy.CreateSimulatedProxy;
import crw.event.output.proxy.ProxyExecutePath;
import crw.event.output.service.AllocationRequest;
import dreaam.wizard.EventWizardInt;
import edu.uci.ics.jung.algorithms.layout.AbstractLayout;
import edu.uci.ics.jung.graph.Graph;
import edu.uci.ics.jung.visualization.VisualizationViewer;
import java.awt.Point;
import java.util.Hashtable;
import sami.DreaamHelper;
import sami.engine.Mediator;
import sami.event.ProxyStartTimer;
import sami.event.ProxyTimerExpired;
import sami.event.ReflectedEventSpecification;
import sami.event.RefreshTasks;
import sami.event.StartTimer;
import sami.event.TaskStarted;
import sami.event.TimerExpired;
import sami.mission.Edge;
import sami.mission.InEdge;
import sami.mission.InTokenRequirement;
import sami.mission.MissionPlanSpecification;
import sami.mission.OutEdge;
import sami.mission.OutTokenRequirement;
import sami.mission.Place;
import sami.mission.TokenRequirement;
import sami.mission.Transition;
import sami.mission.Vertex;
import sami.mission.Vertex.FunctionMode;

/**
 *
 * @author nbb
 */
public class CrwEventWizard implements EventWizardInt {

    private static final InTokenRequirement noReq = new InTokenRequirement(TokenRequirement.MatchCriteria.None, null);
    private static final InTokenRequirement hasAllRt = new InTokenRequirement(TokenRequirement.MatchCriteria.RelevantToken, TokenRequirement.MatchQuantity.All);
    private static final InTokenRequirement noProxies = new InTokenRequirement(TokenRequirement.MatchCriteria.AnyProxy, TokenRequirement.MatchQuantity.None);
    private static final InTokenRequirement min1G = new InTokenRequirement(TokenRequirement.MatchCriteria.Generic, TokenRequirement.MatchQuantity.GreaterThanEqualTo, 1);

    private static final OutTokenRequirement addAllRt = new OutTokenRequirement(TokenRequirement.MatchCriteria.RelevantToken, TokenRequirement.MatchQuantity.All, TokenRequirement.MatchAction.Add);
    private static final OutTokenRequirement takeAllRt = new OutTokenRequirement(TokenRequirement.MatchCriteria.RelevantToken, TokenRequirement.MatchQuantity.All, TokenRequirement.MatchAction.Take);
    private static final OutTokenRequirement add1G = new OutTokenRequirement(TokenRequirement.MatchCriteria.Generic, TokenRequirement.MatchQuantity.Number, TokenRequirement.MatchAction.Add, 1);
    private static final OutTokenRequirement con1G = new OutTokenRequirement(TokenRequirement.MatchCriteria.Generic, TokenRequirement.MatchQuantity.Number, TokenRequirement.MatchAction.Consume, 1);
    private static final OutTokenRequirement takeAllP = new OutTokenRequirement(TokenRequirement.MatchCriteria.AnyProxy, TokenRequirement.MatchQuantity.All, TokenRequirement.MatchAction.Take);
    private static final OutTokenRequirement take1G = new OutTokenRequirement(TokenRequirement.MatchCriteria.Generic, TokenRequirement.MatchQuantity.Number, TokenRequirement.MatchAction.Take, 1);

    private static final Hashtable<Class, RequiredTokenType> oeToTokenNeeded = new Hashtable<Class, RequiredTokenType>();
    private static final Hashtable<Class, RequiredInRequirement> ieToMinInReq = new Hashtable<Class, RequiredInRequirement>();
    private static final Hashtable<Class, RequiredOutRequirement> ieToMinOutReq = new Hashtable<Class, RequiredOutRequirement>();
    
    private enum RequiredTokenType {

        Task, Proxy, Generic, None
    };
    
    private enum RequiredInRequirement {
        HasRt, None
    };
    
    private enum RequiredOutRequirement {
        AddRt, TakeRt, None
    };

    static {
        oeToTokenNeeded.put(OperatorCreateArea.class, RequiredTokenType.None);
        oeToTokenNeeded.put(OperatorSelectBoat.class, RequiredTokenType.Proxy);
        oeToTokenNeeded.put(OperatorSelectBoatList.class, RequiredTokenType.Proxy);
        oeToTokenNeeded.put(CreateSimulatedProxy.class, RequiredTokenType.None);
        oeToTokenNeeded.put(ConnectExistingProxy.class, RequiredTokenType.None);
        oeToTokenNeeded.put(StartTimer.class, RequiredTokenType.None);
        oeToTokenNeeded.put(ProxyStartTimer.class, RequiredTokenType.Proxy);
        oeToTokenNeeded.put(AllocationRequest.class, RequiredTokenType.Task);
        oeToTokenNeeded.put(RefreshTasks.class, RequiredTokenType.Task);
        oeToTokenNeeded.put(ProxyExecutePath.class, RequiredTokenType.Task);

        ieToMinInReq.put(OperatorCreatedArea.class, RequiredInRequirement.None);
        ieToMinInReq.put(ProxyCreated.class, RequiredInRequirement.None);
        ieToMinInReq.put(TimerExpired.class, RequiredInRequirement.None);
        ieToMinInReq.put(OperatorSelectsBoat.class, RequiredInRequirement.HasRt);
        ieToMinInReq.put(OperatorSelectsBoatList.class, RequiredInRequirement.HasRt);
        ieToMinInReq.put(ProxyTimerExpired.class, RequiredInRequirement.HasRt);
        ieToMinInReq.put(OperatorAcceptsAllocation.class, RequiredInRequirement.HasRt);
        ieToMinInReq.put(OperatorRejectsAllocation.class, RequiredInRequirement.HasRt);
        ieToMinInReq.put(TaskStarted.class, RequiredInRequirement.HasRt);
        ieToMinInReq.put(ProxyPathCompleted.class, RequiredInRequirement.HasRt);
        ieToMinInReq.put(AllocationResponse.class, RequiredInRequirement.HasRt);

        ieToMinOutReq.put(OperatorCreatedArea.class, RequiredOutRequirement.None);
        ieToMinOutReq.put(ProxyCreated.class, RequiredOutRequirement.AddRt);
        ieToMinOutReq.put(TimerExpired.class, RequiredOutRequirement.None);
        ieToMinOutReq.put(OperatorSelectsBoat.class, RequiredOutRequirement.TakeRt);
        ieToMinOutReq.put(OperatorSelectsBoatList.class, RequiredOutRequirement.TakeRt);
        ieToMinOutReq.put(ProxyTimerExpired.class, RequiredOutRequirement.TakeRt);
        ieToMinOutReq.put(OperatorAcceptsAllocation.class, RequiredOutRequirement.TakeRt);
        ieToMinOutReq.put(OperatorRejectsAllocation.class, RequiredOutRequirement.TakeRt);
        ieToMinOutReq.put(TaskStarted.class, RequiredOutRequirement.TakeRt);
        ieToMinOutReq.put(ProxyPathCompleted.class, RequiredOutRequirement.TakeRt);
        ieToMinOutReq.put(AllocationResponse.class, RequiredOutRequirement.TakeRt);
    }

    public CrwEventWizard() {
    }

    @Override
    public boolean runWizard(String eventClassname, MissionPlanSpecification mSpec, Point graphPoint, Graph<Vertex, Edge> dsgGraph, AbstractLayout<Vertex, Edge> layout, VisualizationViewer<Vertex, Edge> vv) {
        ReflectedEventSpecification eventSpec;
        if (eventClassname.equalsIgnoreCase(ProxyExecutePath.class.getName())) {
            // Create vertices
            // P1: Do Path [ProxyExecutePath]
            Place p1 = new Place("Do path", Vertex.FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            eventSpec = new ReflectedEventSpecification(ProxyExecutePath.class.getName());
            p1.addEventSpec(eventSpec);
            mSpec.updateEventSpecList(p1, eventSpec);
            graphPoint = DreaamHelper.getVertexFreePoint(vv, graphPoint.getX(), graphPoint.getY(), new int[]{2});
            mSpec.addPlace(p1, graphPoint);
            // T1_2a
            Transition t1_2a = new Transition("Path completed", Vertex.FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            eventSpec = new ReflectedEventSpecification(ProxyPathCompleted.class.getName());
            t1_2a.addEventSpec(eventSpec);
            mSpec.updateEventSpecList(t1_2a, eventSpec);
            Point upperPoint = DreaamHelper.getVertexFreePoint(vv, graphPoint.getX(), graphPoint.getY(), new int[]{1});
            mSpec.addTransition(t1_2a, upperPoint);
            // T1_2b
            Transition t1_2b = new Transition("", Vertex.FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            Point lowerPoint = DreaamHelper.getVertexFreePoint(vv, graphPoint.getX(), graphPoint.getY(), new int[]{3});
            mSpec.addTransition(t1_2b, lowerPoint);
            // P2a: Proxy collector
            Place p2a = new Place("Proxy collector", Vertex.FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            upperPoint = DreaamHelper.getVertexFreePoint(vv, upperPoint.getX(), upperPoint.getY(), new int[]{1});
            mSpec.addPlace(p2a, upperPoint);
            // P2b: All done
            Place p2b = new Place("All done", Vertex.FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            lowerPoint = DreaamHelper.getVertexFreePoint(vv, lowerPoint.getX(), lowerPoint.getY(), new int[]{3});
            mSpec.addPlace(p2b, lowerPoint);
            // T2_3
            Transition t2_3 = new Transition("", Vertex.FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            graphPoint = DreaamHelper.getVertexFreePoint(vv, upperPoint.getX(), upperPoint.getY(), new int[]{3});
            mSpec.addTransition(t2_3, graphPoint);
            // P3: All proxies now done
            Place p3 = new Place("All proxies", Vertex.FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            graphPoint = DreaamHelper.getVertexFreePoint(vv, graphPoint.getX(), graphPoint.getY(), new int[]{2});
            mSpec.addPlace(p3, graphPoint);

            // Create Edges
            // IE-P1-T1_2a: has RP
            InEdge ie_P1_T1_2a = new InEdge(p1, t1_2a, FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            ie_P1_T1_2a.addTokenRequirement(hasAllRt);
            mSpec.addEdge(ie_P1_T1_2a, p1, t1_2a);
            // OE-T1_2a-P2a: take RP
            OutEdge oe_T1_2a_P2a = new OutEdge(t1_2a, p2a, FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            oe_T1_2a_P2a.addTokenRequirement(takeAllRt);
            mSpec.addEdge(oe_T1_2a_P2a, t1_2a, p2a);
            // IE-P1-T1_2b: empty P
            InEdge ie_P1_T1_2b = new InEdge(p1, t1_2b, FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            ie_P1_T1_2b.addTokenRequirement(noProxies);
            mSpec.addEdge(ie_P1_T1_2b, p1, t1_2b);
            // OE-T1_2b-P2b: add G
            OutEdge oe_T1_2b_P2b = new OutEdge(t1_2b, p2b, FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            oe_T1_2b_P2b.addTokenRequirement(add1G);
            mSpec.addEdge(oe_T1_2b_P2b, t1_2b, p2b);
            // IE-P2a-T2_3: no req
            InEdge ie_P2a_T2_3 = new InEdge(p2a, t2_3, FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            ie_P2a_T2_3.addTokenRequirement(noReq);
            mSpec.addEdge(ie_P2a_T2_3, p2a, t2_3);
            // IE-P2b-T2_3: has G
            InEdge ie_P2b_T2_3 = new InEdge(p2b, t2_3, FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            ie_P2b_T2_3.addTokenRequirement(min1G);
            mSpec.addEdge(ie_P2b_T2_3, p2b, t2_3);
            // OE-T2_3-P3: [consume G, take P]
            OutEdge oe_T2_3_P3 = new OutEdge(t2_3, p3, FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            oe_T2_3_P3.addTokenRequirement(con1G);
            oe_T2_3_P3.addTokenRequirement(takeAllP);
            mSpec.addEdge(oe_T2_3_P3, t2_3, p3);
        } else if (eventClassname.equalsIgnoreCase(OperatorCreateArea.class.getName())) {
            // Create vertices
            // P1: Create [OperatorCreateArea]
            Place p1 = new Place("Create", Vertex.FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            eventSpec = new ReflectedEventSpecification(OperatorCreateArea.class.getName());
            p1.addEventSpec(eventSpec);
            mSpec.updateEventSpecList(p1, eventSpec);
            graphPoint = DreaamHelper.getVertexFreePoint(vv, graphPoint.getX(), graphPoint.getY(), new int[]{2});
            mSpec.addPlace(p1, graphPoint);
            // T1_2: Created [OperatorCreatedArea]
            Transition t1_2 = new Transition("Created", Vertex.FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            eventSpec = new ReflectedEventSpecification(OperatorCreatedArea.class.getName());
            t1_2.addEventSpec(eventSpec);
            mSpec.updateEventSpecList(t1_2, eventSpec);
            Point upperPoint = DreaamHelper.getVertexFreePoint(vv, graphPoint.getX(), graphPoint.getY(), new int[]{1});
            mSpec.addTransition(t1_2, upperPoint);
            // P3
            Place p2 = new Place("", Vertex.FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            graphPoint = DreaamHelper.getVertexFreePoint(vv, graphPoint.getX(), graphPoint.getY(), new int[]{3});
            mSpec.addPlace(p2, graphPoint);
            // Create Edges
            // IE-P1-T1_2: has G
            InEdge ie_P2b_T2_3 = new InEdge(p1, t1_2, FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            ie_P2b_T2_3.addTokenRequirement(min1G);
            mSpec.addEdge(ie_P2b_T2_3, p1, t1_2);
            // OE-T1_2-P2: take G
            OutEdge oe_T2_3_P3 = new OutEdge(t1_2, p2, FunctionMode.Nominal, Mediator.getInstance().getProject().getAndIncLastElementId());
            oe_T2_3_P3.addTokenRequirement(take1G);
            mSpec.addEdge(oe_T2_3_P3, t1_2, p2);
        }
        computeRequirements(mSpec);
        return false;
    }

    public boolean computeRequirements(MissionPlanSpecification mSpec) {

        return false;
    }
}
