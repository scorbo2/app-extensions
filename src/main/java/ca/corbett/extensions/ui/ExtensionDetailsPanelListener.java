package ca.corbett.extensions.ui;

/**
 * Allows client code to listen for enable/disable checkbox events on
 * an ExtensionDetailsPanel.
 *
 * @author scorbo2
 */
public interface ExtensionDetailsPanelListener {

    public void extensionEnabled(ExtensionDetailsPanel source, String className);

    public void extensionDisabled(ExtensionDetailsPanel source, String className);

}
