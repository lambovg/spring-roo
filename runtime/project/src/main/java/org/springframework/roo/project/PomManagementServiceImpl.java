package org.springframework.roo.project;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Service;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.springframework.roo.file.monitor.FileMonitorService;
import org.springframework.roo.metadata.MetadataDependencyRegistry;
import org.springframework.roo.metadata.MetadataService;
import org.springframework.roo.process.manager.FileManager;
import org.springframework.roo.project.maven.Pom;
import org.springframework.roo.project.maven.PomFactory;
import org.springframework.roo.shell.Shell;
import org.springframework.roo.support.logging.HandlerUtils;
import org.springframework.roo.support.osgi.OSGiUtils;
import org.springframework.roo.support.util.FileUtils;
import org.springframework.roo.support.util.XmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

@Component
@Service
public class PomManagementServiceImpl implements PomManagementService {

  private BundleContext context;
  private static final Logger LOGGER = HandlerUtils.getLogger(PomManagementServiceImpl.class);

  protected void activate(final ComponentContext cContext) {
    context = cContext.getBundleContext();
    final File projectDirectory =
        new File(StringUtils.defaultIfEmpty(OSGiUtils.getRooWorkingDirectory(cContext),
            FileUtils.CURRENT_DIRECTORY));
    projectRootDirectory = FileUtils.getCanonicalPath(projectDirectory);
  }

  private static class PomComparator implements Comparator<String> {
    private final Map<String, Pom> pomMap;

    /**
     * Constructor
     * 
     * @param pomMap
     */
    private PomComparator(final Map<String, Pom> pomMap) {
      this.pomMap = pomMap;
    }

    public int compare(final String s1, final String s2) {
      final String p1 = pomMap.get(s1).getRoot() + SEPARATOR;
      final String p2 = pomMap.get(s2).getRoot() + SEPARATOR;
      if (p1.startsWith(p2)) {
        return -1;
      } else if (p2.startsWith(p1)) {
        return 1;
      }
      return 0;
    }
  }

  private static final String SEPARATOR = File.separator;
  private static final String DEFAULT_POM_NAME = "pom.xml";
  private static final String DEFAULT_RELATIVE_PATH = ".." + SEPARATOR + DEFAULT_POM_NAME;

  FileManager fileManager;
  FileMonitorService fileMonitorService;
  MetadataDependencyRegistry metadataDependencyRegistry;
  MetadataService metadataService;
  PomFactory pomFactory;
  Shell shell;

  private String focusedModulePath;
  private final Map<String, Pom> pomMap = new LinkedHashMap<String, Pom>();
  private String projectRootDirectory;
  private final Set<String> toBeParsed = new HashSet<String>();

  /**
   * For test cases to set up the state of this service
   * 
   * @param pom the POM to add (required)
   */
  void addPom(final Pom pom) {
    pomMap.put(pom.getPath(), pom);
  }

  private void findUnparsedPoms() {
    for (final String change : getFileMonitorService().getDirtyFiles(getClass().getName())) {
      if (change.endsWith(DEFAULT_POM_NAME)) {
        toBeParsed.add(change);
      }
    }
  }

  public Pom getFocusedModule() {
    updatePomCache();
    if (focusedModulePath == null && getRootPom() != null) {
      focusedModulePath = getRootPom().getPath();
    }
    return getPomFromPath(focusedModulePath);
  }

  public String getFocusedModuleName() {
    if (getFocusedModule() == null) {
      return "";
    }
    return getFocusedModule().getModuleName();
  }

  public Pom getModuleForFileIdentifier(final String fileIdentifier) {
    updatePomCache();
    String startingPoint = FileUtils.getFirstDirectory(fileIdentifier);
    String pomPath = FileUtils.ensureTrailingSeparator(startingPoint) + DEFAULT_POM_NAME;
    File pom = new File(pomPath);
    while (!pom.exists()) {
      if (startingPoint.equals(SEPARATOR)) {
        break;
      }
      startingPoint = StringUtils.removeEnd(startingPoint, SEPARATOR);

      if (startingPoint.lastIndexOf(SEPARATOR) < 0) {
        break;
      }
      startingPoint = startingPoint.substring(0, startingPoint.lastIndexOf(SEPARATOR));
      startingPoint = StringUtils.removeEnd(startingPoint, SEPARATOR);

      pomPath = FileUtils.ensureTrailingSeparator(startingPoint) + DEFAULT_POM_NAME;
      pom = new File(pomPath);
    }
    return getPomFromPath(pomPath);
  }

  private String getModuleName(final String pomDirectory) {
    final String normalisedRootPath = FileUtils.ensureTrailingSeparator(projectRootDirectory);
    final String normalisedPomDirectory = FileUtils.ensureTrailingSeparator(pomDirectory);
    final String moduleName = StringUtils.removeStart(normalisedPomDirectory, normalisedRootPath);
    return StringUtils.stripEnd(moduleName, SEPARATOR);
  }

  public Collection<String> getModuleNames() {
    final Set<String> moduleNames = new HashSet<String>();
    for (final Pom module : pomMap.values()) {
      moduleNames.add(module.getModuleName());
    }
    return moduleNames;
  }

  public Pom getPomFromModuleName(final String moduleName) {
    for (final Pom pom : getPoms()) {
      if (pom.getModuleName().equals(moduleName)) {
        return pom;
      }
    }
    return null;
  }

  public Pom getPomFromPath(final String pomPath) {
    updatePomCache();
    return pomMap.get(pomPath);
  }

  public Collection<Pom> getPoms() {
    updatePomCache();
    return new ArrayList<Pom>(pomMap.values());
  }

  public Pom getRootPom() {
    updatePomCache();
    return pomMap.get(projectRootDirectory + SEPARATOR + DEFAULT_POM_NAME);
  }

  private Set<Pom> parseUnparsedPoms() {
    final Map<String, String> pomModuleMap = new HashMap<String, String>();
    final Set<Pom> newPoms = new HashSet<Pom>();
    for (final Iterator<String> iter = toBeParsed.iterator(); iter.hasNext();) {
      final String pathToChangedPom = iter.next();
      if (new File(pathToChangedPom).exists()) {
        String pomContents = "";
        try {
          pomContents =
              org.apache.commons.io.FileUtils.readFileToString(new File(pathToChangedPom));
        } catch (IOException ignored) {
        }
        if (StringUtils.isNotBlank(pomContents)) {
          final Element rootElement = XmlUtils.stringToElement(pomContents);
          resolvePoms(rootElement, pathToChangedPom, pomModuleMap);
          final String moduleName = getModuleName(FileUtils.getFirstDirectory(pathToChangedPom));
          final Pom pom = getPomFactory().getInstance(rootElement, pathToChangedPom, moduleName);
          Validate.notNull(pom, "POM is null for module '%s' and path '%s'", moduleName,
              pathToChangedPom);
          pomMap.put(pathToChangedPom, pom);
          newPoms.add(pom);
          iter.remove();
        }
      }
    }
    return newPoms;
  }

  private void resolveChildModulePoms(final Element pomRoot, final String pomPath,
      final Map<String, String> pomSet) {
    for (final Element module : XmlUtils.findElements("/project/modules/module", pomRoot)) {
      final String moduleName = module.getTextContent();
      if (StringUtils.isNotBlank(moduleName)) {
        final String modulePath = resolveRelativePath(pomPath, moduleName);
        final boolean alreadyDiscovered = pomSet.containsKey(modulePath);
        pomSet.put(modulePath, moduleName);
        if (!alreadyDiscovered) {
          final Document pomDocument =
              XmlUtils.readXml(getFileManager().getInputStream(modulePath));
          final Element root = pomDocument.getDocumentElement();
          resolvePoms(root, modulePath, pomSet);
        }
      }
    }
  }

  private void resolveParentPom(final String pomPath, final Map<String, String> pomSet,
      final Element parentElement) {
    final String relativePath =
        XmlUtils.getTextContent("/relativePath", parentElement, DEFAULT_RELATIVE_PATH);
    final String parentPomPath = resolveRelativePath(pomPath, relativePath);
    final boolean alreadyDiscovered = pomSet.containsKey(parentPomPath);
    if (!alreadyDiscovered) {
      pomSet.put(parentPomPath, pomSet.get(parentPomPath));
      if (new File(parentPomPath).isFile()) {
        final Document pomDocument =
            XmlUtils.readXml(getFileManager().getInputStream(parentPomPath));
        final Element root = pomDocument.getDocumentElement();
        resolvePoms(root, parentPomPath, pomSet);
      }
    }
  }

  private void resolvePoms(final Element pomRoot, final String pomPath,
      final Map<String, String> pomSet) {
    pomSet.put(pomPath, pomSet.get(pomPath)); // ensures this key exists

    final Element parentElement = XmlUtils.findFirstElement("/project/parent", pomRoot);
    if (parentElement != null) {
      resolveParentPom(pomPath, pomSet, parentElement);
    }

    resolveChildModulePoms(pomRoot, pomPath, pomSet);
  }

  private String resolveRelativePath(String relativeTo, final String relativePath) {
    if (relativeTo.endsWith(SEPARATOR)) {
      relativeTo = relativeTo.substring(0, relativeTo.length() - 1);
    }
    while (new File(relativeTo).isFile()) {
      relativeTo = relativeTo.substring(0, relativeTo.lastIndexOf(SEPARATOR));
    }
    final String[] relativePathSegments = relativePath.split(FileUtils.getFileSeparatorAsRegex());

    int backCount = 0;
    for (final String relativePathSegment : relativePathSegments) {
      if (relativePathSegment.equals("..")) {
        backCount++;
      } else {
        break;
      }
    }
    final StringBuilder sb = new StringBuilder();
    for (int i = backCount; i < relativePathSegments.length; i++) {
      sb.append(relativePathSegments[i]);
      sb.append(SEPARATOR);
    }

    while (backCount > 0) {
      relativeTo = relativeTo.substring(0, relativeTo.lastIndexOf(SEPARATOR));
      backCount--;
    }
    String path = relativeTo + SEPARATOR + sb.toString();
    if (new File(path).isDirectory()) {
      path = path + DEFAULT_POM_NAME;
    }
    if (path.endsWith(SEPARATOR)) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }

  public void setFocusedModule(final Pom focusedModule) {
    Validate.notNull(focusedModule, "Module required");
    if (focusedModule.getPath().equals(focusedModulePath)) {
      return;
    }
    focusedModulePath = focusedModule.getPath();
    getShell().setPromptPath(focusedModule.getModuleName());
  }

  private void sortPomMap() {
    final List<String> sortedPomPaths = new ArrayList<String>(pomMap.keySet());
    Collections.sort(sortedPomPaths, new PomComparator(pomMap));
    final Map<String, Pom> sortedPomMap = new LinkedHashMap<String, Pom>();
    for (final String pomPath : sortedPomPaths) {
      sortedPomMap.put(pomPath, pomMap.get(pomPath));
    }
    pomMap.clear();
    pomMap.putAll(sortedPomMap);
  }

  private void updatePomCache() {
    findUnparsedPoms();
    final Collection<Pom> newPoms = parseUnparsedPoms();
    if (!newPoms.isEmpty()) {
      sortPomMap();
    }
    updateProjectMetadataForModules(newPoms);
  }

  private void updateProjectMetadataForModules(final Iterable<Pom> newPoms) {
    for (final Pom pom : newPoms) {
      final String projectMetadataId = ProjectMetadata.getProjectIdentifier(pom.getModuleName());
      getMetadataService().evictAndGet(projectMetadataId);
      getMetadataDependencyRegistry().notifyDownstream(projectMetadataId);
    }
  }

  /**
   * Method to get FileMonitorService Service implementation
   * 
   * @return
   */
  public FileMonitorService getFileMonitorService() {
    if (fileMonitorService == null) {
      // Get all Services implement FileMonitorService interface
      try {
        ServiceReference<?>[] references =
            context.getAllServiceReferences(FileMonitorService.class.getName(), null);

        for (ServiceReference<?> ref : references) {
          fileMonitorService = (FileMonitorService) context.getService(ref);
          return fileMonitorService;
        }

        return null;

      } catch (InvalidSyntaxException e) {
        LOGGER.warning("Cannot load FileMonitorService on PomManagementServiceImpl.");
        return null;
      }
    } else {
      return fileMonitorService;
    }
  }

  /**
   * Method to get PomFactory Service implementation
   * 
   * @return
   */
  public PomFactory getPomFactory() {
    if (pomFactory == null) {
      // Get all Services implement PomFactory interface
      try {
        ServiceReference<?>[] references =
            context.getAllServiceReferences(PomFactory.class.getName(), null);

        for (ServiceReference<?> ref : references) {
          pomFactory = (PomFactory) context.getService(ref);
          return pomFactory;
        }

        return null;

      } catch (InvalidSyntaxException e) {
        LOGGER.warning("Cannot load PomFactory on PomManagementServiceImpl.");
        return null;
      }
    } else {
      return pomFactory;
    }
  }

  /**
   * Method to get FileManager Service implementation
   * 
   * @return
   */
  public FileManager getFileManager() {
    if (fileManager == null) {
      // Get all Services implement FileManager interface
      try {
        ServiceReference<?>[] references =
            context.getAllServiceReferences(FileManager.class.getName(), null);

        for (ServiceReference<?> ref : references) {
          fileManager = (FileManager) context.getService(ref);
          return fileManager;
        }

        return null;

      } catch (InvalidSyntaxException e) {
        LOGGER.warning("Cannot load FileManager on PomManagementServiceImpl.");
        return null;
      }
    } else {
      return fileManager;
    }
  }

  /**
   * Method to get MetadataService Service implementation
   * 
   * @return
   */
  public MetadataService getMetadataService() {
    if (metadataService == null) {
      // Get all Services implement MetadataService interface
      try {
        ServiceReference<?>[] references =
            context.getAllServiceReferences(MetadataService.class.getName(), null);

        for (ServiceReference<?> ref : references) {
          metadataService = (MetadataService) context.getService(ref);
          return metadataService;
        }

        return null;

      } catch (InvalidSyntaxException e) {
        LOGGER.warning("Cannot load MetadataService on PomManagementServiceImpl.");
        return null;
      }
    } else {
      return metadataService;
    }
  }

  /**
   * Method to get MetadataDependencyRegistry Service implementation
   * 
   * @return
   */
  public MetadataDependencyRegistry getMetadataDependencyRegistry() {
    if (metadataDependencyRegistry == null) {
      // Get all Services implement MetadataDependencyRegistry interface
      try {
        ServiceReference<?>[] references =
            context.getAllServiceReferences(MetadataDependencyRegistry.class.getName(), null);

        for (ServiceReference<?> ref : references) {
          metadataDependencyRegistry = (MetadataDependencyRegistry) context.getService(ref);
          return metadataDependencyRegistry;
        }

        return null;

      } catch (InvalidSyntaxException e) {
        LOGGER.warning("Cannot load MetadataDependencyRegistry on PomManagementServiceImpl.");
        return null;
      }
    } else {
      return metadataDependencyRegistry;
    }
  }


  /**
   * Method to get Shell Service implementation
   * 
   * @return
   */
  public Shell getShell() {
    if (shell == null) {
      // Get all Services implement Shell interface
      try {
        ServiceReference<?>[] references =
            context.getAllServiceReferences(Shell.class.getName(), null);

        for (ServiceReference<?> ref : references) {
          shell = (Shell) context.getService(ref);
          return shell;
        }

        return null;

      } catch (InvalidSyntaxException e) {
        LOGGER.warning("Cannot load Shell on PomManagementServiceImpl.");
        return null;
      }
    } else {
      return shell;
    }
  }
}
