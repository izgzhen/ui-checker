/*
 * Intent.java - part of the GATOR project
 *
 * Copyright (c) 2020 The Ohio State University
 *
 * This file is distributed under the terms described in LICENSE in the
 * root directory.
 */

package soot.jimple.infoflow.android.iccta;/* Created at 5/4/20 by zhen */

import java.util.*;

public class Intent {
    private String component;
    private String componentPackage;
    private String componentClass = null;

    private String action = null;
    private Set<String> categories = new HashSet<String>();
    private Map<String, String> extras = new HashMap<String, String>();
    private String dataScheme = null;
    private String dataHost = null;
    private String uri = null;
    private int dataPort = -1;
    private String dataPath;
    private String data;
    private int flags;
    private App app;
    private LoggingPoint point;
    private String authority;
    private String dataType;

    public Intent(App app, LoggingPoint point) {
        this.app = app;
        this.point = point;
    }

    public void seal() {
    }

    public LoggingPoint getLoggingPoint() {
        return point;
    }

    public boolean isImplicit() {
        /*
         * if (null != action && !action.isEmpty()) { return true; }
         */
        if (component != null
                && !component.isEmpty()
                && !component.contains("*")
                && !component.contains("NULL-CONSTANT"))
            return false;

        return true;
    }

    @Override
    public Intent clone() {
        Intent intent = new Intent(app, point);
        intent.component = component;
        intent.componentPackage = componentPackage;
        intent.componentClass = componentClass;

        intent.action = action;
        Set<String> tmpCategories = new HashSet<String>();
        for (String str : categories) {
            tmpCategories.add(str);
        }
        intent.categories = tmpCategories;
        Map<String, String> tmpExtras = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : extras.entrySet()) {
            tmpExtras.put(entry.getKey(), entry.getValue());
        }
        intent.extras = tmpExtras;
        intent.dataScheme = dataScheme;
        intent.dataHost = dataHost;
        intent.dataPort = dataPort;
        intent.dataPath = dataPath;
        intent.data = data;
        intent.flags = flags;
        intent.app = app;

        return intent;
    }

    @Override
    public String toString() {
        return "Intent [component="
                + component
                + ", componentPackage="
                + componentPackage
                + ", componentClass="
                + componentClass
                + ", action="
                + action
                + ", categories="
                + categories
                /* + ", extras=" + extras */
                + ", dataScheme="
                + dataScheme
                + ", dataHost="
                + dataHost
                + ", dataPort="
                + dataPort
                + ", dataPath="
                + dataPath
                + ", data="
                + data
                + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (o == null)
            return false;

        return this.toString().equals(o.toString());
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    public String getComponent() {
        return component;
    }

    public void setComponent(String component) {
        this.component = component;
        if (component.contains("/") && !component.startsWith("/")) {
            setComponentPackage(component.split("/")[0]);
            if (!component.endsWith("/"))
                setComponentClass(component.split("/")[1]);
        }
    }

    public String getComponentPackage() {
        return componentPackage;
    }

    public void setComponentPackage(String componentPackage) {
        this.componentPackage = componentPackage;
    }

    public String getComponentClass() {
        return componentClass;
    }

    public void setComponentClass(String componentClass) {
        this.componentClass = componentClass;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        if (action.equals("<INTENT>"))
            return;
        this.action = action;
    }

    public Set<String> getCategories() {
        return categories;
    }

    public void setCategories(Set<String> categories) {
        categories.remove("<INTENT>");
        if (!categories.isEmpty())
            this.categories = categories;
    }

    public Map<String, String> getExtras() {
        return extras;
    }

    public void setExtras(Map<String, String> extras) {
        this.extras = extras;
    }

    public String getDataScheme() {
        return dataScheme;
    }

    public void setDataScheme(String dataScheme) {
        if (dataScheme.equals("(.*)"))
            return;
        this.dataScheme = dataScheme;
    }

    public String getDataHost() {
        return dataHost;
    }

    public void setDataHost(String dataHost) {
        if (dataHost.equals("(.*)"))
            return;
        this.dataHost = dataHost;
    }

    public int getDataPort() {
        return dataPort;
    }

    public void setDataPort(int dataPort) {
        if (dataPort == 0)
            dataPort = -1;
        this.dataPort = dataPort;
    }

    public String getDataPath() {
        return dataPath;
    }

    public void setDataPath(String dataPath) {
        if (dataPath.equals("(.*)"))
            return;
        this.dataPath = dataPath;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        if (data.equals("(.*)"))
            return;
        this.data = data;
        if (data.contains("://")) {
            if (dataScheme == null) {
                dataScheme = data.substring(0, data.indexOf("://"));
                data = data.substring(data.indexOf("://") + 3);
                if (dataScheme.contains(".*"))
                    dataScheme = null;
            }
            if (!data.isEmpty()) {
                if (dataHost == null) {
                    if (data.contains("/"))
                        dataHost = data.substring(0, data.indexOf("/"));
                    else
                        dataHost = data;
                    if (dataHost.contains(".*"))
                        dataHost = null;
                }
                if (dataPath == null && !data.isEmpty()) {
                    if (data.contains("/"))
                        dataPath = data.substring(data.indexOf("/") + 1);
                    else
                        dataPath = data;
                    if (dataPath.contains(".*"))
                        dataPath = null;
                }
            }
        }
    }

    public int getFlags() {
        return flags;
    }

    public void setFlags(int flags) {
        this.flags = flags;
    }

    public void setAuthority(String value) {
        authority = value;
    }

    public String getAuthority() {
        return authority;
    }

    public App getApp() {
        return app;
    }

    public boolean hasImpreciseValues() {
        String string = toString();

        return isImprecise(string);
    }

    private boolean isImprecise(String string) {
        if (string == null)
            return false;

        // This should not occur in intents, except due to effects of Harvester.
        if (string.toLowerCase().contains("harvester"))
            return true;

        if (string.contains(".*"))
            return true;

        return false;
    }

    public boolean hasImportantImpreciseValues() {

        return isImprecise(getAction()) || isImprecise(authority);
    }

    public List<Ic3Data.Application.Component> resolve(List<Ic3Data.Application.Component> list) {
        boolean isImplicit = isImplicit();
        String packageName = getComponentPackage();
        String componentName = getComponent();
        if (componentName != null && componentName.contains("/")) {
            if (packageName == null)
                packageName = componentName.substring(0, componentName.indexOf("/"));
            componentName = componentName.substring(componentName.indexOf("/") + 1);
        }
        if (packageName != null && packageName.contains(".*"))
            packageName = null;
        if (componentName != null && componentName.contains(".*"))
            componentName = null;
        if (componentName != null && componentName.contains("NULL-CONSTANT"))
            componentName = null;
        List<Ic3Data.Application.Component> results = new ArrayList<Ic3Data.Application.Component>();
        for (Ic3Data.Application.Component component : list) {
            if (packageName != null) {
                // (Usually optional) Set an explicit application package name that limits the
                // components this Intent will resolve to. If left to the default value of null,
                // all components in all applications will considered. If non-null, the Intent
                // can only match the components in the given application package.
                if (!component.getApp().getAppName().equals(packageName))
                    continue;
            }

            if (componentName != null) {
                if (componentName.equals(component.getName())) {
                    results.add(component);
                    continue;
                }
            }

            boolean exported;
            /*
             * The default value depends on whether the activity contains intent filters.
             * The absence of any filters means that the activity can be invoked only by
             * specifying its exact class name. This implies that the activity is intended
             * only for application-internal use (since others would not know the class
             * name). So in this case, the default value is "false". On the other hand, the
             * presence of at least one filter implies that the activity is intended for
             * external use, so the default value is "true".
             */

            if (component.getIntentFiltersCount() > 0)
                exported = true;
            else
                exported = false;

            if (component.hasExported())
                // Overriden
                exported = component.getExported();

            if (!exported) {
                // Whether or not the activity can be launched by components of other
                // applications - "true" if it can be, and "false" if not. If "false", the
                // activity can be launched only by components of the same application or
                // applications with the same user ID.
                if (!component.getApp().getAppName().equals(getApp().getAppName())) {
                    continue;
                }

            }
            if (isImplicit) {
                // Android automatically applies the the CATEGORY_DEFAULT category to all
                // implicit intents passed to startActivity() and startActivityForResult(). So
                // if you want your activity to receive implicit intents, it must include a
                // category for "android.intent.category.DEFAULT" in its intent filters (as
                // shown in the previous <intent-filter> example.
                // However, it might be due to imprecision reasonse we assume it's implicit
                // although it's not...
                // Therefore we assume that this intent might be explicit nevertheless...
                /*
                 * if (getLoggingPoint().getCalleeMethodSignature().contains("startActivity"))
                 * categories.add("android.intent.category.DEFAULT");
                 */

                for (Ic3Data.Application.Component.IntentFilter filter : component.getIntentFiltersList()) {
                    boolean hasSpecifiedAnAction = false;
                    boolean passedAction = false;
                    boolean passedCategory = false;
                    boolean passedData = false;
                    // String scheme = null, host = null, path = null, authority = null;

                    // See the <data> tag in the SDK help
                    /*
                     * All the <data> elements contained within the same <intent-filter> element
                     * contribute to the same filter. So, for example, the following filter
                     * specification, <intent-filter . . . > <data android:scheme="something"
                     * android:host="project.example.com" /> . . . </intent-filter>
                     *
                     * is equivalent to this one: <intent-filter . . . > <data
                     * android:scheme="something" /> <data android:host="project.example.com" /> . .
                     * . </intent-filter>
                     */
                    // Comment by me:
                    // Thus, the attributes seem to be independent of each other and I can save each
                    // one
                    // in a list, as the nesting of data tags do not matter:
                    List<String> schemes = new ArrayList<String>();
                    List<String> hosts = new ArrayList<String>();
                    List<String> paths = new ArrayList<String>();
                    List<Integer> ports = new ArrayList<Integer>();
                    List<String> authorities = new ArrayList<String>();
                    List<String> types = new ArrayList<String>();

                    if (getAction() == null)
                        passedAction = true;

                    boolean categoryVisited = false;

                    for (Ic3Data.Attribute attribute : filter.getAttributesList()) {
                        switch (attribute.getKind()) {
                            case ACTION:
                                hasSpecifiedAnAction = true;
                                if (attribute.getValueList().contains(getAction()))
                                    passedAction = true;
                                break;
                            case CATEGORY:
                                passedCategory = attribute.getValueList().containsAll(getCategories());
                                categoryVisited = true;
                                break;
                            case HOST:
                                if (attribute.getValueCount() != 1)
                                    throw new RuntimeException("Valuecount != 1");
                                hosts.add(attribute.getValueList().get(0));
                                break;
                            case SCHEME:
                                if (attribute.getValueCount() != 1)
                                    throw new RuntimeException("Valuecount != 1");
                                schemes.add(attribute.getValueList().get(0));
                                break;
                            case PORT:
                                if (attribute.getValueCount() != 1)
                                    throw new RuntimeException("Valuecount != 1");
                                ports.add(Integer.parseInt(attribute.getValueList().get(0)));
                                break;
                            case PATH:
                                if (attribute.getValueCount() != 1)
                                    throw new RuntimeException("Valuecount != 1");
                                paths.add(attribute.getValueList().get(0));
                                break;
                            case AUTHORITY:
                                if (attribute.getValueCount() != 1)
                                    throw new RuntimeException("Valuecount != 1");
                                authorities.add(attribute.getValueList().get(0));
                                break;
                            case TYPE:
                                if (attribute.getValueCount() != 1)
                                    throw new RuntimeException("Valuecount != 1");
                                types.add(attribute.getValueList().get(0));
                                break;
                            // the following attributes are ignored in intent resolution_
                            case PRIORITY:
                                break;
                            case EXTRA:
                                break;
                            case FLAG:
                                break;
                            default:
                                throw new RuntimeException("Unexpected: " + attribute.getKind().toString());

                        }
                    }

                    if (!categoryVisited) {
                        if (getCategories().isEmpty()) {
                            passedCategory = true;
                        }
                    }

                    // If both the scheme and host are not specified, the path is ignored.
                    if (schemes.isEmpty() && hosts.isEmpty())
                        paths.clear();
                    // If a scheme is not specified, the host is ignored.
                    if (schemes.isEmpty())
                        hosts.clear();
                    // If a host is not specified, the port is ignored.
                    if (hosts.isEmpty())
                        ports.clear();

                    // When the URI in an intent is compared to a URI specification in a filter,
                    // it's compared only to the parts of the URI included in the filter
                    boolean matchesURI = false;
                    if (!schemes.isEmpty())
                        matchesURI |= schemes.contains(getDataScheme());
                    if (!authorities.isEmpty())
                        matchesURI |= authorities.contains(getAuthority());
                    if (!paths.isEmpty()) {
                        for (String input : paths) {
                            String regex = ("\\Q" + input + "\\E").replace("*", "\\E.*\\Q");
                            String s = getDataPath();
                            if (s == null)
                                s = "";
                            matchesURI |= s.matches(regex);
                        }
                    }
                    if (!hosts.isEmpty())
                        matchesURI |= hosts.contains(getDataHost());
                    if (!ports.isEmpty())
                        matchesURI |= ports.contains(getDataPort());
                    if (!types.isEmpty())
                        matchesURI |= types.contains(getType());

                    // TODO: When does it "contain a URI"?
                    boolean containsURI = getDataScheme() != null;
                    boolean intentFilterSpecifiesURI = !schemes.isEmpty();

                    // An intent that contains neither a URI nor a MIME type passes the test only if
                    // the filter does not specify any URIs or MIME types.
                    if (getType() == null
                            && getAuthority() == null
                            && getDataScheme() == null
                            && getDataPort() == -1
                            && getDataPath() == null
                            && getDataHost() == null) {
                        passedData = ports.isEmpty()
                                && paths.isEmpty()
                                && hosts.isEmpty()
                                && authorities.isEmpty()
                                && schemes.isEmpty()
                                && types.isEmpty();
                    }

                    // An intent that contains a URI but no MIME type (neither explicit nor
                    // inferable from the URI) passes the test only if its URI matches the filter's
                    // URI format and the filter likewise does not specify a MIME type.
                    if (containsURI && getType() == null) {
                        passedData = matchesURI && types.isEmpty();
                    }

                    // An intent that contains a MIME type but not a URI passes the test only if the
                    // filter lists the same MIME type and does not specify a URI format.
                    if (getType() != null && !containsURI) {
                        if (getType() == null)
                            passedData = !intentFilterSpecifiesURI;
                        else
                            passedData = types.contains(getType()) && !intentFilterSpecifiesURI;
                    }

                    /*
                     * An intent that contains both a URI and a MIME type (either explicit or
                     * inferable from the URI) passes the MIME type part of the test only if that
                     * type matches a type listed in the filter. It passes the URI part of the test
                     * either if its URI matches a URI in the filter or if it has a content: or
                     * file: URI and the filter does not specify a URI. In other words, a component
                     * is presumed to support content: and file: data if its filter lists only a
                     * MIME type.
                     */
                    if (getType() != null && containsURI) {
                        boolean mimetype = types.contains(getType());
                        boolean urlpart = matchesURI;
                        if (getDataScheme() != null)
                            urlpart = urlpart
                                    || ((getDataScheme().equals("content") || getDataScheme().equals("file"))
                                    && !intentFilterSpecifiesURI);

                        passedData = mimetype && urlpart;
                    }
                    boolean passedActionPart = passedAction || !hasSpecifiedAnAction;

                    // Imprecision in IC3: Assume, we have passed the data test...
                    /*
                     * if (getData() == null && getDataHost() == null && getDataScheme() == null &&
                     * getDataPath() == null) passedData = true;
                     */
                    if (passedActionPart && passedCategory && passedData) {
                        // This intent filter succeeded
                        results.add(component);

                        break;
                    }
                }
            }

        }
        return results;
    }

    public void setType(String value) {
        if (value.isEmpty() || value.equals("(.*)"))
            return;
        this.dataType = value;
    }

    public String getType() {
        return dataType;
    }
}
