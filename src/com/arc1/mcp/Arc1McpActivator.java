package com.arc1.mcp;

import org.eclipse.core.runtime.Plugin;
import org.osgi.framework.BundleContext;

public class Arc1McpActivator extends Plugin {

    public static final String PLUGIN_ID = "com.arc1.mcp";

    private static Arc1McpActivator instance;

    public static Arc1McpActivator getDefault() {
        return instance;
    }

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        instance = null;
        super.stop(context);
    }
}
