package ca.corbett.extensions;

import ca.corbett.extras.properties.AbstractProperty;
import ca.corbett.extras.properties.PropertiesDialog;
import ca.corbett.extras.properties.PropertiesManager;

import java.awt.Frame;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class encapulates a PropertiesManager and an ExtensionManager together into
 * one handy utility that client projects can use more easily than the old approach
 * of having the client projects maintain these things separately.
 * <p>
 * <b>Enabling and disabling</b><br>
 * Both this class and ExtensionManager have methods for enabling and disabling an
 * extension. But ExtensionManager knows nothing about this class. This class therefore
 * tries to automatically reconcile these status flags across the two classes, and
 * generally you shouldn't need to worry about it. If an extension is enabled or
 * disabled in ExtensionManager, this class will notice the change and will enable
 * or disable all extension properties as needed. This means that you shouldn't see
 * properties from disabled extensions in the PropertiesDialog, but the values for
 * them will still be saved and loaded to the propsFile. When you re-enable an
 * extension, either in this class or in ExtensionManager, then its properties will
 * once again show up in the PropertiesDialog.
 * <p>
 * <b>Loading and saving</b><br>
 * Unlike sc-util 1.8, there's nothing wonky that client apps need to do in order
 * to load AppProperties and Extensions. You can simply invoke load() in this class
 * and it will all just work. Any extensions that were disabled the last time you
 * invoked save() will be correctly loaded in a disabled state - this means that their
 * property values will be loaded correctly, but they won't show up in the PropertiesDialog
 * until the extension is enabled again.
 *
 * @author scorbo2
 * @since 2024-12-30
 */
public abstract class AppProperties {

    private static final Logger logger = Logger.getLogger(AppProperties.class.getName());

    protected final PropertiesManager propsManager;
    protected final ExtensionManager extManager;

    private final String appName;
    private final File propsFile;

    /**
     * If your application has no ExtensionManager, you can construct an AppPreferences
     * instance by simply supplying your application name (used in dialog titles and
     * as a header in the props file) along with the props file itself.
     *
     * @param appName   The name of this application.
     * @param propsFile A File in which properties will be stored for this application.
     */
    protected AppProperties(String appName, File propsFile) {
        this(appName, propsFile, null);
    }

    /**
     * If your application has an ExtensionManager, you can supply it here and this
     * class will handle loading and saving properties for all enabled extensions.
     *
     * @param appName    The name of this application.
     * @param propsFile  A File in which properties will be stored for this application.
     * @param extManager An optional ExtensionManager (can be null).
     */
    protected AppProperties(String appName, File propsFile, ExtensionManager extManager) {
        this.appName = appName;
        this.propsFile = propsFile;
        this.extManager = extManager;
        propsManager = createPropertiesManager();
    }

    /**
     * If you want to take some action after props are loaded (for example, to set window
     * dimensions or other ui state), you can override this method and put your updates
     * AFTER you invoke super load().
     */
    public void load() {
        try {
            propsManager.load();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception loading application properties: " + e.getMessage(), e);
        }

        // Now that we have loaded all props, figure out which extensions should be enabled/disabled:
        if (extManager != null) {
            for (Object ext : extManager.getAllLoadedExtensions()) {
                AppExtension extension = (AppExtension) ext;

                // Note we don't call isExtensionEnabled here because this is the one spot where we
                // don't care what ExtensionManager has to say on the subject... we only care
                // if the extension is disabled in our properties list, and we'll tell
                // ExtensionManager whether or not it's enabled.
                boolean isEnabled = propsManager.getPropertiesInstance().getBoolean("extension.enabled." + extension.getClass().getName(), true);
                extManager.setExtensionEnabled(extension.getClass().getName(), isEnabled, false);

                // Also enable or disable any properties for this extension:
                List<AbstractProperty> disabledProps = extension.getConfigProperties();
                for (AbstractProperty prop : disabledProps) {
                    if (propsManager.getProperty(prop.getFullyQualifiedName()) != null) {
                        propsManager.getProperty(prop.getFullyQualifiedName()).setEnabled(isEnabled);
                    }
                }
            }
        }
    }

    /**
     * If you have specific properties you wish to set on save (window dimensions or other
     * variable stuff), you can override this method and invoke super save() AFTER you have
     * updated your property values.
     */
    public void save() {
        reconcileExtensionEnabledStatus();
        propsManager.save();
    }

    /**
     * Generates and shows a PropertiesDialog to allow the user to view or change any
     * of the current properties. If the user okays the dialog, changes are automatically saved.
     *
     * @param owner The owning Frame (so we can make the dialog modal to that Frame).
     */
    public void showDialog(Frame owner) {
        reconcileExtensionEnabledStatus();
        PropertiesDialog dialog = propsManager.generateDialog(owner, appName + " properties", true, 24);
        dialog.setVisible(true);

        if (dialog.wasOkayed()) {
            save();
        }
    }

    /**
     * Reports whether the named extension is currently enabled. If no such extension is found,
     * this will return whatever defaultValue you specify. Note: this is shorthand for
     * propsManager.getPropertiesInstance().getBoolean("extension.enabled."+extName, defaultValue);
     * <p>
     * Note: enabled status is stored in the ExtensionManager as well as here. In the case
     * of a discrepancy, the ExtensionManager will be considered the source of truth. That means
     * that this method might have the side effect of enabling/disabling an extension here
     * in AppPreferences if we check and find that ExtensionManager's answer doesn't match ours.
     *
     * @param extName      The class name of the extension to check.
     * @param defaultValue A value to return if the status can't be found.
     * @return Whether the named extension is enabled.
     */
    public boolean isExtensionEnabled(String extName, boolean defaultValue) {
        boolean enabledInProps = propsManager.getPropertiesInstance().getBoolean("extension.enabled." + extName, defaultValue);

        if (extManager != null) {
            boolean isActuallyEnabled = extManager.isExtensionEnabled(extName);

            // If extManager has a different opinion than we do, update ourselves:
            if (enabledInProps != isActuallyEnabled) {
                propsManager.getPropertiesInstance().setBoolean("extension.enabled." + extName, isActuallyEnabled);
                enabledInProps = isActuallyEnabled;
            }
        }

        return enabledInProps;
    }

    /**
     * Enables or disables the specified extension. We will also update ExtensionManager,
     * if we have one.
     *
     * @param extName The class name of the extension to enable/disable
     * @param value   The new enabled status for that extension.
     */
    public void setExtensionEnabled(String extName, boolean value) {
        propsManager.getPropertiesInstance().setBoolean("extension.enabled." + extName, value);

        // Also notify ExtensionManager about this change:
        if (extManager != null) {
            extManager.setExtensionEnabled(extName, value);
        }
    }

    /**
     * Override this to specify whatever properties your application needs. This method
     * will be invoked automatically upon creation.
     *
     * @return A List of zero or more AbstractProperty instances.
     */
    protected abstract List<AbstractProperty> createInternalProperties();

    /**
     * Invoked internally to create and configure our PropertiesManager instance.
     *
     * @return A configured PropertiesManager.
     */
    private PropertiesManager createPropertiesManager() {
        List<AbstractProperty> props = new ArrayList<>();
        props.addAll(createInternalProperties());

        // The name of this method is misleading, because ALL extensions are enabled by default.
        // But that's okay. We'll load all properties for all extensions, and then the load()
        // method can handling disabling extensions and hiding properties for those extensions.
        if (extManager != null) {
            props.addAll(extManager.getAllEnabledExtensionProperties());
        }
        PropertiesManager manager = new PropertiesManager(propsFile, props, appName + " application properties");

        return manager;
    }

    /**
     * Invoked internally to reconcile the extension enabled status between our managed
     * properties list and our ExtensionManager, if we have one. These can get out of sync
     * if the ExtensionManager enables or disables an extension. There's currently no way
     * to "push" such changes from ExtensionManager to this class, because ExtensionManager
     * doesn't know that we exist. So, before we do anything that requires us to know
     * about extensions being enabled or not, we have to "pull" the statuses to ensure
     * that our managed list is up to date with what's specified in ExtensionManager.
     * <p>
     * Side note: in your app, if you need to enable or disable an extension, you can either
     * do it using ExtensionManager.setExtensionEnabled or via the setExtensionEnabled
     * method in this class. Either way, this class will keep itself in sync with ExtensionManager.
     */
    private void reconcileExtensionEnabledStatus() {
        if (extManager == null) {
            return;
        }

        // Loop through all extensions and use our isExtensionEnabled method to
        // pull the current enabled stats from extManager. Yeah, methods starting
        // with "is" probably shouldn't have side effects like this, but meh.
        for (Object ext : extManager.getAllLoadedExtensions()) {
            AppExtension extension = (AppExtension) ext;
            boolean isEnabled = isExtensionEnabled(extension.getClass().getName(), false);

            // Also set the enabled status of each extension property:
            List<AbstractProperty> props = extension.getConfigProperties();
            for (AbstractProperty prop : props) {
                if (propsManager.getProperty(prop.getFullyQualifiedName()) != null) {
                    propsManager.getProperty(prop.getFullyQualifiedName()).setEnabled(isEnabled);
                }
            }
        }
    }
}
