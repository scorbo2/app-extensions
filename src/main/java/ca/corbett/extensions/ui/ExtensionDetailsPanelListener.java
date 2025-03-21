package ca.corbett.extensions.ui;

/**
 * @author scorbo2
 */
public interface ExtensionDetailsPanelListener {

    public void extensionEnabled(ExtensionDetailsPanel source, String className);

    public void extensionDisabled(ExtensionDetailsPanel source, String className);

}
