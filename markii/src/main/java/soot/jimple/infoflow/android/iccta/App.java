package soot.jimple.infoflow.android.iccta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import soot.jimple.infoflow.android.iccta.Ic3Data.Application.Component;
import soot.jimple.infoflow.android.iccta.Ic3Data.Application.Component.IntentFilter;
import soot.jimple.infoflow.android.iccta.Ic3Data.Attribute;

public class App {
    private Set<LoggingPoint> loggingPoints = new HashSet<LoggingPoint>();
    private int analysisTime;
    private String analysisName;
    private String appName;
    private Object metadata;
    private boolean seal;
    private List<Component> componentsList;

    private Map<Component, Map<Component.ExitPoint, Set<Intent>>> componentIntents = Maps.newHashMap();

    public App(String analysisName, String appName, Object metadata) {
        this.analysisName = analysisName;
        this.appName = appName;
        this.metadata = metadata;
    }

    public App(String analysisName, String appName) {
        this(analysisName, appName, null);
    }

    public String getAppName() {
        return appName;
    }

    public String getAnalysisName() {
        return analysisName;
    }

    public Set<LoggingPoint> getLoggingPoints() {
        if (seal)
            return Collections.unmodifiableSet(loggingPoints);
        else
            return loggingPoints;
    }

    public void setLoggingPoints(Set<LoggingPoint> loggingPoints) {
        this.loggingPoints = loggingPoints;
    }

    public int getAnalysisTime() {
        return analysisTime;
    }

    public void setAnalysisTime(int analysisTime) {
        this.analysisTime = analysisTime;
    }

    public void dump() {
        for (LoggingPoint loggingPoint : loggingPoints) {
            System.out.println("----------------------------");
            System.out.println(loggingPoint.getCallerMethodSignature() + "/" + loggingPoint.getCalleeMethodSignature());
            for (Intent intent : loggingPoint.getIntents()) {
                System.out.println("  " + "Component: " + intent.getComponent());
                System.out.println("  " + "Categories: " + intent.getCategories());
                System.out.println("  " + "Action: " + intent.getAction());
            }
        }

        System.out.println("Analysis time: " + analysisTime);
    }

    public int getResultCount() {
        int c = 0;
        for (LoggingPoint lp : loggingPoints) {
            c += lp.getIntents().size();
        }
        return c;
    }

    public Object getMetadata() {
        return metadata;
    }

    public int getSatisfiedLPs() {
        int satisfied = 0;
        for (LoggingPoint c : loggingPoints) {
            if (!c.getIntents().isEmpty())
                satisfied++;
        }
        return satisfied;
    }

    public void seal() {
        seal = true;
        for (LoggingPoint p : getLoggingPoints()) {
            p.seal();

        }
    }

    public Set<Intent> getIntents() {
        Set<Intent> intents = new HashSet<Intent>();
        for (LoggingPoint p : getLoggingPoints())
            intents.addAll(p.getIntents());
        return intents;
    }

    public void setComponentList(List<Component> componentsList) {
        for (Component c : componentsList)
            c.setApp(this);
        this.componentsList = componentsList;
    }

    public List<Component> getComponentList() {
        return componentsList;
    }

    public void addIntent(Component component, Component.ExitPoint exitPoint, Intent intent) {
        componentIntents.computeIfAbsent(component, k -> Maps.newHashMap())
                .computeIfAbsent(exitPoint, k -> Sets.newHashSet()).add(intent);
    }

    public Set<Intent> getIntents(Component component, Component.ExitPoint exitPoint) {
        Map<Component.ExitPoint, Set<Intent>> pIntents = componentIntents.get(component);
        if (pIntents == null) return null;
        return pIntents.get(exitPoint);
    }
}

class LoggingPoint {
    private String callerMethodSignature;
    private String calleeMethodSignature;
    private int stmtSequence;
    private Set<Intent> intents = new HashSet<Intent>();
    int id;
    private App app;
    private boolean sealed;
    public String extraInformation;

    public LoggingPoint(App app) {
        this.app = app;
    }

    public void seal() {
        sealed = true;
        for (Intent i : getIntents())
            i.seal();
    }

    public String getCallerMethodSignature() {
        return callerMethodSignature;
    }

    public void setCallerMethodSignature(String callerMethodSignature) {
        this.callerMethodSignature = callerMethodSignature;
    }

    public String getCalleeMethodSignature() {
        return calleeMethodSignature;
    }

    public void setCalleeMethodSignature(String calleeMethodSignature) {
        this.calleeMethodSignature = calleeMethodSignature;
    }

    public int getStmtSequence() {
        return stmtSequence;
    }

    public void setStmtSequence(int stmtSequence) {
        this.stmtSequence = stmtSequence;
    }

    public Set<Intent> getIntents() {
        if (sealed)
            return Collections.unmodifiableSet(intents);
        else
            return intents;
    }

    public void setIntents(Set<Intent> intents) {
        if (sealed)
            throw new IllegalStateException();
        this.intents = intents;
    }

    public void setId(int id) {
        this.id = id;
    }

    @Override
    public int hashCode() {
        // Do not change the implementation.
        int result = 1;
        /*
         * result = prime result + ((calleeMethodSignature == null) ? 0 :
         * calleeMethodSignature .hashCode()); result = prime result +
         * ((callerMethodSignature == null) ? 0 : callerMethodSignature .hashCode());
         */
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        LoggingPoint other = (LoggingPoint) obj;
        if (calleeMethodSignature == null) {
            if (other.calleeMethodSignature != null)
                return false;
        } else if (!calleeMethodSignature.equals(other.calleeMethodSignature))
            return false;
        if (callerMethodSignature == null) {
            if (other.callerMethodSignature != null)
                return false;
        } else if (!callerMethodSignature.equals(other.callerMethodSignature))
            return false;
        if (this.stmtSequence != other.stmtSequence)
            return false;
        if (this.app == other.app) {
            if (this.id == other.id)
                return true;
            else
                return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return String.valueOf(id);
    }

    public boolean equalsSimilar(LoggingPoint pointDest) {
        String shortenedA = getCalleeMethodSignature().substring(getCalleeMethodSignature().indexOf(":"));
        String shortenedB = pointDest.getCalleeMethodSignature()
                .substring(pointDest.getCalleeMethodSignature().indexOf(":"));
        boolean b = getCallerMethodSignature().equals(pointDest.getCallerMethodSignature())
                && shortenedA.equals(shortenedB);
        // getCalleeMethodSignature().equals(pointDest.getCalleeMethodSignature());
        return b;
    }

    public boolean hasResults() {
        boolean noResult = getIntents().isEmpty()
                || (getIntents().size() == 1 && getIntents().iterator().next() instanceof EmptyIntent);
        return !noResult;
    }

}

class EmptyIntent extends Intent {

    public EmptyIntent(App app, LoggingPoint point) {
        super(app, point);
    }

    @Override
    public String toString() {
        return "Not found";
    }

}

