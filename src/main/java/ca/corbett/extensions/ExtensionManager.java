package ca.corbett.extensions;

import ca.corbett.extras.io.FileSystemUtil;
import ca.corbett.extras.properties.AbstractProperty;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides a mechanism for scanning for and loading instances of AppExtension
 * for any given application. Extend this class with your specific implementation type
 * of AppExtension and then point it to a directory containing jar files that
 * provide implementations of that class.
 *
 * @param <T> Any class that implements AppExtension - this is the class we'll scan for.
 * @author scorbo2
 * @since 2023-11-11
 */
public abstract class ExtensionManager<T extends AppExtension> {

    protected static final Logger logger = Logger.getLogger(ExtensionManager.class.getName());

    private final Map<String, ExtensionWrapper> loadedExtensions;

    public ExtensionManager() {
        loadedExtensions = new HashMap<>();
    }

    /**
     * Reports how many extensions have been loaded.
     *
     * @return A count of loaded extensions (enabled or not).
     */
    public int getLoadedExtensionCount() {
        return loadedExtensions.size();
    }

    /**
     * Reports whether an extension with the given class name is currently loaded.
     * Note you can also do getLoadedExtension(className) and check for null.
     *
     * @param className The fully qualified name of the extension to look for.
     * @return True if the named extension has been loaded by this manager.
     */
    public boolean isExtensionLoaded(String className) {
        return loadedExtensions.get(className) != null;
    }

    /**
     * Reports whether the loaded extension with the given class name is enabled.
     *
     * @param className the fully qualified class name of the extension in question.
     * @return True if the extension is enabled, false if disabled or not found.
     */
    public boolean isExtensionEnabled(String className) {
        ExtensionWrapper wrapper = loadedExtensions.get(className);
        return wrapper != null && wrapper.isEnabled;
    }

    /**
     * Enables or disables the named extension - this involves sending an onActivate or
     * onDeactivate message to the extension as needed. If the given isEnabled value
     * matches the existing value, no action is taken. If the given extension cannot
     * be found, no action is taken.
     *
     * @param className The fully qualified class name of the extension in question.
     * @param isEnabled Whether to enable or disable the extension.
     */
    public void setExtensionEnabled(String className, boolean isEnabled) {
        setExtensionEnabled(className, isEnabled, true);
    }

    /**
     * Enables or disabled the named extension - if notify is true, will send the extension
     * an onActivate or onDeactivate message as needed. If the given isEnabled value
     * matches the existing value, no action is taken. If the given extension cannot
     * be found, no action is taken.
     *
     * @param className The fully qualified class name of the extension in question.
     * @param isEnabled Whether to enable or disable the extension.
     * @param notify    Whether to send a message to the extension notifying them of the change.
     */
    public void setExtensionEnabled(String className, boolean isEnabled, boolean notify) {
        ExtensionWrapper wrapper = loadedExtensions.get(className);
        if (wrapper != null) {
            // Toggling to enabled?
            if (isEnabled && !wrapper.isEnabled) {
                wrapper.isEnabled = true;
                if (notify) {
                    wrapper.extension.onActivate();
                }
            }

            // Toggling to disabled?
            if (!isEnabled && wrapper.isEnabled) {
                wrapper.isEnabled = false;
                if (notify) {
                    wrapper.extension.onDeactivate();
                }
            }
        }
    }

    /**
     * Returns the source jar from which the given extension was loaded, or null if this
     * extension was added manually with addExtension().
     *
     * @param className the fully qualified class name of the extension in question.
     * @return The File representing the extensions source jar, or null if not found or built-in.
     */
    public File getSourceJar(String className) {
        ExtensionWrapper wrapper = loadedExtensions.get(className);
        return wrapper != null ? wrapper.sourceJar : null;
    }

    /**
     * Returns the extension instance for the given class name, if one exists.
     *
     * @param className the fully qualified class name of the extension in question.
     * @return The extension.
     */
    public T getLoadedExtension(String className) {
        ExtensionWrapper wrapper = loadedExtensions.get(className);
        return wrapper != null ? wrapper.extension : null;
    }

    /**
     * Returns a list of all loaded extensions - beware that this method will return
     * extensions even if they are marked as disabled! If you only want to get the
     * extensions that are currently enabled, use getEnabledLoadedExtensions() instead.
     *
     * @return A List of zero or more extensions sorted by name.
     */
    public List<T> getAllLoadedExtensions() {
        List<ExtensionWrapper> wrapperList = getAllLoadedExtensionWrappers();
        List<T> extList = new ArrayList<>();
        for (ExtensionWrapper wrapper : wrapperList) {
            extList.add(wrapper.extension);
        }
        return extList;
    }

    /**
     * Returns a list of all loaded extensions that are marked as enabled.
     * This list is computed each time this method is called as extensions can
     * be enabled and disabled at pretty much any point.
     *
     * @return A List of zero or more enabled and loaded extensions.
     */
    public List<T> getEnabledLoadedExtensions() {
        List<ExtensionWrapper> wrapperList = getAllLoadedExtensionWrappers();
        List<T> extList = new ArrayList<>();
        for (ExtensionWrapper wrapper : wrapperList) {
            if (wrapper.isEnabled) {
                extList.add(wrapper.extension);
            }
        }
        return extList;
    }

    /**
     * Invoke this to interrogate each enabled extension for their config properties, if any,
     * and return them in a list.
     *
     * @return The combined list of properties of all enabled extensions.
     */
    public List<AbstractProperty> getAllEnabledExtensionProperties() {
        List<AbstractProperty> propList = new ArrayList<>();
        List<ExtensionWrapper> wrapperList = getAllLoadedExtensionWrappers();
        for (ExtensionWrapper wrapper : wrapperList) {
            if (wrapper.extension == null || !wrapper.isEnabled) {
                continue;
            }
            List<AbstractProperty> list = wrapper.extension.getConfigProperties();
            if (list != null) {
                propList.addAll(list);
            }
        }
        return propList;
    }

    /**
     * Programmatically adds an extension to our list - this was originally intended for testing
     * purposes, but might be useful as a way for applications to supply built-in extensions
     * without having to package them in separate jar files with the distribution. Remember that
     * extension load order matters! If you are supplying built-in extensions, it's probably
     * better to invoke this before you load extensions from jar files on disk. This is so that
     * getAllExtensionProperties can work as intended - i.e. extensions have the ability to
     * overwrite config properties from earlier-loaed extensions.
     * <p>
     * The extension will not receive an onActivate() notification from this method.
     * Use activateAll() to start up extensions.
     *
     * @param extension The extension class to be added.
     * @param isEnabled Whether to enable this extension immediately or not.
     */
    public void addExtension(T extension, boolean isEnabled) {
        ExtensionWrapper wrapper = new ExtensionWrapper();
        wrapper.sourceJar = null;
        wrapper.isEnabled = isEnabled;
        wrapper.extension = extension;
        loadedExtensions.put(extension.getClass().getName(), wrapper);
    }

    /**
     * Sends an onActivate() message to each enabled extension, to let them know that we're
     * starting up - use deactivateAll() to signal shutdown.
     */
    public void activateAll() {
        for (ExtensionWrapper wrapper : getAllLoadedExtensionWrappers()) {
            if (wrapper.extension == null) {
                continue;
            }
            if (wrapper.isEnabled) {
                wrapper.extension.onActivate();
            }
        }
    }

    /**
     * Sends an onDeactivate() message to each enabled extension, to let them know that we're
     * shutting down - use activateAll() to signal startup.
     */
    public void deactivateAll() {
        for (ExtensionWrapper wrapper : getAllLoadedExtensionWrappers()) {
            if (wrapper.extension == null) {
                continue;
            }
            if (wrapper.isEnabled) {
                wrapper.extension.onDeactivate();
            }
        }
    }

    /**
     * Scans the given directory looking for candidate jar files that contain an extension matching
     * the given parameters. For each jar that is found, an attempt will be made to load the
     * extension class out of that jar file. All successfully loaded extension classes will
     * then be loaded into this ExtensionManager.
     * <p>
     * Note that this is a shorthand way of doing this more manually (or jar by jar) via
     * the findCandidateExtensionJars, extractExtInfo, and jarFileMeetsRequirements methods.
     * Generally, this is the better entry point, but if you have a specific jar file that
     * you want to scan and load from, those other methods can be used instead.
     * </p>
     *
     * @param directory      The directory to scan.
     * @param extClass       The implementation class to look for.
     * @param appName        The application name to match against.
     * @param minimumVersion The minimum application version that the extension must target.
     */
    public void loadExtensions(File directory, Class<T> extClass, String appName, String minimumVersion) {
        Map<File, AppExtensionInfo> map = findCandidateExtensionJars(directory, appName, minimumVersion);
        if (map.isEmpty()) {
            return;
        }
        List<File> jarList = new ArrayList<>(map.keySet());
        jarList.sort(new Comparator<File>() {
            @Override
            public int compare(File o1, File o2) {
                return o1.getAbsolutePath().compareTo(o2.getAbsolutePath());
            }

        });
        for (File jarFile : jarList) {
            T extension = loadExtensionFromJar(jarFile, extClass);
            if (extension != null) {
                ExtensionWrapper wrapper = new ExtensionWrapper();
                wrapper.sourceJar = jarFile;
                wrapper.extension = extension;
                wrapper.isEnabled = true;
                loadedExtensions.put(extension.getClass().getName(), wrapper);
            }
        }
    }

    /**
     * Scans the given directory looking for Jar files that contain an extInfo file, and
     * if one is found, will check its parameters against the given appName and minimumVersion
     * to make sure the extension would work for that application. The return is a Map of
     * File to AppExtensionInfo, which can then be loaded via one of the loadExtension methods.
     * Note that this method does not actually try to load the extension, it simply scans
     * to find which jar file would be good candidates for loading. This can therefore be
     * used for autodiscovery of extension jars in a given directory safely, without actually
     * loading them. Note that both appName and minimumVersion are optional (you can pass null
     * to disable those checks), but the result may be jar files that contain extensions
     * for the wrong app, or for the wrong version of the app, or both.
     *
     * @param directory      The directory to scan (will be scanned recursively).
     * @param appName        The application name to check for, or null to skip this check.
     * @param minimumVersion The minimum required app version, or null to skip this check.
     * @return A Map of jar files to AppExtensionInfo objects.
     */
    public Map<File, AppExtensionInfo> findCandidateExtensionJars(File directory, String appName, String minimumVersion) {
        Map<File, AppExtensionInfo> map = new HashMap<>();

        // Start by finding all jar files in the target directory:
        List<File> jarFiles = FileSystemUtil.findFiles(directory, true, "jar");

        // Now try scanning for an extInfo file:
        for (File jarFile : jarFiles) {
            AppExtensionInfo extInfo = extractExtInfo(jarFile);
            if (extInfo == null) {
                continue;
            }
            if (jarFileMeetsRequirements(jarFile, extInfo, appName, minimumVersion)) {
                map.put(jarFile, extInfo);
            }

            // Check app name if one was given:
            if (appName != null && !appName.equals(extInfo.getTargetAppName())) {
                logger.log(Level.WARNING,
                        "findCandidateExtensionJars: skipping jar {0} because target app name \"{1}\" does not match given app name \"{2}\".",
                        new Object[]{jarFile.getAbsolutePath(), extInfo.getTargetAppName(), appName});
                continue;
            }

            // Check minimum app version if one was given:
            if (minimumVersion != null) {
                try {
                    float minVersion = Float.valueOf(minimumVersion);
                    float extRequires = extInfo.getTargetAppVersion() == null ? -1 : Float.valueOf(extInfo.getTargetAppVersion());
                    if (extRequires < minVersion) {
                        logger.log(Level.WARNING, "findCandidateExtensionJars: Jar file {0} contains an older extension with version {1}, below the required version of {2}; skipping.",
                                new Object[]{jarFile.getAbsolutePath(), extInfo.getTargetAppVersion(), minimumVersion});
                        continue;
                    }
                } catch (NumberFormatException nfe) {
                    logger.log(Level.WARNING, "findCandidateExtensionJars: unable to parse version information for jar file {0}: App version: \"{1}\", extension targets version \"{2}\".",
                            new Object[]{jarFile.getAbsolutePath(), minimumVersion, extInfo.getTargetAppVersion()});
                }

                // If it passes the above checks, add it to the returned map:
            }
        }

        return map;
    }

    /**
     * Checks if the given jar file and extension info meet the given requirements (that is,
     * that the application name and minimum version requirements are met). This does not guarantee
     * that an extension can be successfully loaded out of the given jar file, but it is
     * a pretty good indicator.
     *
     * @param jarFile        The jar file in question.
     * @param extInfo        The extension info that was extracted from that jar via extractExtInfo
     * @param appName        The name of the application to check for.
     * @param minimumVersion The minimum app version that the extension must target.
     * @return true if the jar file looks good, false otherwise.
     */
    public boolean jarFileMeetsRequirements(File jarFile, AppExtensionInfo extInfo, String appName, String minimumVersion) {
        // Check app name if one was given:
        if (appName != null && !appName.equals(extInfo.getTargetAppName())) {
            logger.log(Level.WARNING,
                    "jarFileMeetsRequirements: skipping jar {0} because target app name \"{1}\" does not match given app name \"{2}\".",
                    new Object[]{jarFile.getAbsolutePath(), extInfo.getTargetAppName(), appName});
            return false;
        }

        // Check minimum app version if one was given:
        if (minimumVersion != null) {
            try {
                float minVersion = Float.parseFloat(minimumVersion);
                float extRequires = extInfo.getTargetAppVersion() == null ? -1 : Float.parseFloat(extInfo.getTargetAppVersion());
                if (extRequires < minVersion) {
                    logger.log(Level.WARNING, "jarFileMeetsRequirements: Jar file {0} contains an older extension with version {1}, below the required version of {2}; skipping.",
                            new Object[]{jarFile.getAbsolutePath(), extInfo.getTargetAppVersion(), minimumVersion});
                    return false;
                }
            } catch (NumberFormatException nfe) {
                logger.log(Level.WARNING, "jarFileMeetsRequirements: unable to parse version information for jar file {0}: App version: \"{1}\", extension targets version \"{2}\".",
                        new Object[]{jarFile.getAbsolutePath(), minimumVersion, extInfo.getTargetAppVersion()});
                return false;
            }
        }
        return true;
    }

    /**
     * Scans the given jar file looking for any classes that matches T. The first matching
     * class found will be loaded as an extension of type T and returned. Multiple extension
     * implementations in the same jar file is therefore not supported.
     *
     * @param jarFile        The jar file to scan.
     * @param extensionClass The implementing class to look for.
     * @return An implementation of T if one could be found and loaded, otherwise null.
     */
    public T loadExtensionFromJar(File jarFile, Class<T> extensionClass) {
        try {
            try (JarFile jar = new JarFile(jarFile.getAbsolutePath())) {
                Enumeration<JarEntry> e = jar.entries();
                URL[] urls = {new URL("jar:file:" + jarFile.getAbsolutePath() + "!/")};
                URLClassLoader cl = URLClassLoader.newInstance(urls);

                while (e.hasMoreElements()) {
                    JarEntry je = e.nextElement();
                    if (je.isDirectory() || !je.getName().endsWith(".class")) {
                        continue;
                    }
                    // -6 because of .class
                    String className = je.getName().substring(0, je.getName().length() - 6);
                    className = className.replace('/', '.');

                    // Check to make sure we don't already have one with this class name:
                    if (getLoadedExtension(className) != null) {
                        logger.log(Level.INFO, "Skipping already loaded extension: {0}", className);
                        continue;
                    }

                    // Load this class:
                    Class candidate = cl.loadClass(className);

                    // What I want to do:
                    //    if (T.isAssignableFrom(candidate))
                    // or:
                    //    if (candidate instanceof T)
                    // But these are both illegal in Java because of type erasure.
                    // T is just a compile-time convenience and it is discarded at runtime.
                    // So, we have to force callers to pass in the class even though we're already
                    // typed with it, sigh.
                    if (extensionClass.isAssignableFrom(candidate)) {
                        logger.log(Level.FINE, "Found qualifying AppExtension class: {0} in jar: {1}",
                                new Object[]{candidate.getCanonicalName(),
                                        jarFile.getAbsolutePath()});
                        return (T) candidate.newInstance();
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Caught exception while loading extension from jar " + jarFile.getAbsolutePath(), e);
            return null;
        }

        logger.log(Level.WARNING, "Jar file {0} contains no suitable extension.", new Object[]{jarFile.getAbsolutePath()});
        return null;
    }

    /**
     * Invoked internally to look for an extInfo file inside the given jar file and
     * attempt to parse an AppExtensionInfo object out of it. Upon success, the newly
     * created AppExtensionInfo is returned. If anything goes wrong, the error is logged
     * and null is returned.
     *
     * @param jarFile The jar file in question.
     * @return An AppExtensionInfo, or null.
     */
    public AppExtensionInfo extractExtInfo(File jarFile) {
        logger.log(Level.FINE, "ExtensionManager.extractExtInfo({0})", jarFile.getAbsolutePath());
        try {
            try (JarFile jar = new JarFile(jarFile.getAbsolutePath())) {
                Enumeration<JarEntry> e = jar.entries();
                while (e.hasMoreElements()) {
                    JarEntry entry = e.nextElement();
                    if (!entry.isDirectory() && entry.getName().endsWith("extInfo")) {
                        String data = FileSystemUtil.readStreamToString(jar.getInputStream(entry), "UTF-8");
                        AppExtensionInfo extInfo = AppExtensionInfo.fromString(data);
                        if (extInfo == null) {
                            logger.log(Level.WARNING, "ExtensionManager.extractExtInfo: jar file {0} contains an invalid extInfo - skipping.", jarFile.getAbsolutePath());
                            return null;
                        }
                        return extInfo;
                    }
                }
            }
        } catch (IOException ioe) {
            logger.log(Level.SEVERE, "ExtensionManager.extractExtInfo: unable to parse jar file " + jarFile.getAbsolutePath(), ioe);
        }

        logger.log(Level.WARNING, "ExtensionManager.extractExtInfo: jar file {0} does not contain an extInfo file.", jarFile.getAbsolutePath());
        return null;
    }

    /**
     * Invoked internally to return a list of all loaded extension wrappers, sorted
     * by the extension name.
     *
     * @return A List of ExtensionWrappers, sorted by extension name;
     */
    protected List<ExtensionWrapper> getAllLoadedExtensionWrappers() {
        List<ExtensionWrapper> wrapperList = new ArrayList<>(loadedExtensions.values());
        wrapperList.sort(null);
        return wrapperList;
    }

    protected class ExtensionWrapper implements Comparable<ExtensionWrapper> {

        boolean isEnabled;
        File sourceJar;
        T extension;

        @Override
        public int compareTo(ExtensionWrapper o) {
            return extension.getInfo().getName().compareTo(o.extension.getInfo().getName());
        }
    }
}
