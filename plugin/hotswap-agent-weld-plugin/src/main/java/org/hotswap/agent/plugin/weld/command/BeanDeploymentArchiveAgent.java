package org.hotswap.agent.plugin.weld.command;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.enterprise.context.SessionScoped;
import javax.enterprise.context.spi.Context;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanAttributes;
import javax.enterprise.inject.spi.CDI;
import javax.servlet.http.HttpSession;

import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.plugin.weld.BeanReloadStrategy;
import org.hotswap.agent.plugin.weld.WeldClassSignatureHelper;
import org.hotswap.agent.plugin.weld.WeldPlugin;
import org.hotswap.agent.plugin.weld.beans.ContextualReloadHelper;
import org.hotswap.agent.util.PluginManagerInvoker;
import org.hotswap.agent.util.ReflectionHelper;
import org.jboss.weld.annotated.enhanced.EnhancedAnnotatedType;
import org.jboss.weld.annotated.enhanced.jlr.EnhancedAnnotatedTypeImpl;
import org.jboss.weld.annotated.slim.SlimAnnotatedType;
import org.jboss.weld.bean.AbstractClassBean;
import org.jboss.weld.bean.ManagedBean;
import org.jboss.weld.bean.attributes.BeanAttributesFactory;
import org.jboss.weld.bean.builtin.BeanManagerProxy;
import org.jboss.weld.bootstrap.spi.BeanDeploymentArchive;
import org.jboss.weld.bootstrap.spi.BeansXml;
import org.jboss.weld.context.AbstractBoundContext;
import org.jboss.weld.context.PassivatingContextWrapper;
import org.jboss.weld.context.beanstore.BeanStore;
import org.jboss.weld.context.beanstore.BoundBeanStore;
import org.jboss.weld.context.beanstore.NamingScheme;
import org.jboss.weld.context.beanstore.http.EagerSessionBeanStore;
import org.jboss.weld.context.http.HttpSessionContextImpl;
import org.jboss.weld.manager.BeanManagerImpl;
import org.jboss.weld.metadata.TypeStore;
import org.jboss.weld.resources.ClassTransformer;
import org.jboss.weld.resources.ReflectionCache;
import org.jboss.weld.resources.ReflectionCacheFactory;
import org.jboss.weld.resources.SharedObjectCache;
import org.jboss.weld.util.Beans;

/**
 * Handles creating and redefinition of bean classes in BeanDeploymentArchive
 *
 * @author Vladimir Dvorak
 * @author alpapad@gmail.com
 */
public class BeanDeploymentArchiveAgent {

    private static AgentLogger LOGGER = AgentLogger.getLogger(BeanDeploymentArchiveAgent.class);

    /**
     * Flag to check the reload status. In unit test we need to wait for reload
     * finishing before the test can continue. Set flag to true in the test class
     * and wait until the flag is false again.
     */
    public static boolean reloadFlag = false;

    private BeanDeploymentArchive deploymentArchive;

    private String archivePath;

    private boolean registered = false;

    /**
     * Register bean archive into BdaAgentRegistry and into WeldPlugin. Current classLoader is  set to
     * beanArchive classLoader.
     *
     * @param appClassLoader the class loader - container or application class loader.
     * @param beanArchive the bean archive to be registered
     * @param beanArchiveType the bean archive type
     */
    public static void registerArchive(ClassLoader appClassLoader, BeanDeploymentArchive beanArchive, String beanArchiveType) {
        BeansXml beansXml = beanArchive.getBeansXml();

        if (beansXml != null && beansXml.getUrl() != null && (beanArchiveType == null || "EXPLICIT".equals(beanArchiveType) || "IMPLICIT".equals(beanArchiveType))) {
            String archivePath = null;
            String beansXmlPath = beansXml.getUrl().getPath();
            if (beansXmlPath.endsWith("META-INF/beans.xml")) {
                archivePath = beansXmlPath.substring(0, beansXmlPath.length() - "META-INF/beans.xml".length());
            } else if (beansXmlPath.endsWith("WEB-INF/beans.xml")) {
                archivePath = beansXmlPath.substring(0, beansXmlPath.length() - "beans.xml".length()) + "classes";
            }
            if (archivePath.endsWith(".jar!/")) {
                archivePath = archivePath.substring(0, archivePath.length() - "!/".length());
            }

            BeanDeploymentArchiveAgent bdaAgent = null;
            try {
                LOGGER.debug("BeanDeploymentArchiveAgent registerArchive bdaId='{}' archivePath='{}'.", beanArchive.getId(), archivePath);
                // check that it is regular file
                // toString() is weird and solves HiearchicalUriException for URI like "file:./src/resources/file.txt".

                @SuppressWarnings("unused")
                File path = new File(archivePath);

                Class<?> registryClass = Class.forName(BdaAgentRegistry.class.getName(), true, appClassLoader);

                boolean contain = (boolean) ReflectionHelper.invoke(null, registryClass, "contains", new Class[] {String.class}, archivePath);

                if (!contain) {
                    bdaAgent = new BeanDeploymentArchiveAgent(beanArchive, archivePath);
                    ReflectionHelper.invoke(null, registryClass, "put", new Class[] {String.class, BeanDeploymentArchiveAgent.class}, archivePath, bdaAgent);
                    bdaAgent.register();
                }
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Unable to watch BeanDeploymentArchive with id={}", beanArchive.getId());
            }
            catch (Exception e) {
                LOGGER.error("registerArchive() exception {}.", e.getMessage());
            }
        } else {
            // TODO:
        }
    }

    private void register() {
        if (!registered) {
            registered = true;
            PluginManagerInvoker.callPluginMethod(WeldPlugin.class, getClass().getClassLoader(),
                    "registerBeanDeplArchivePath", new Class[] { String.class }, new Object[] { archivePath });
        }
    }

    /**
     * Gets the collection of registered BeanDeploymentArchive(s)
     *
     * @return the instances
     */
    public static Collection<BeanDeploymentArchiveAgent> getInstances() {
        return BdaAgentRegistry.values();
    }

    private BeanDeploymentArchiveAgent(BeanDeploymentArchive deploymentArchive, String archivePath) {
        this.deploymentArchive = deploymentArchive;
        this.archivePath = archivePath;
    }

    /**
     * Gets the Bean depoyment ID - bdaId.
     *
     * @return the bdaId
     */
    public String getBdaId() {
        return deploymentArchive.getId();
    }

    /**
     * Gets the archive path.
     *
     * @return the archive path
     */
    public String getArchivePath() {
        return archivePath;
    }

    /**
     * Gets the deployment archive.
     *
     * @return the deployment archive
     */
    public BeanDeploymentArchive getDeploymentArchive() {
        return deploymentArchive;
    }

    /**
     * Recreate proxy classes, Called from BeanClassRefreshCommand.
     *
     * @param classLoader the class loader
     * @param archivePath the bean archive path
     * @param registeredProxiedBeans the registered proxied beans
     * @param beanClassName the bean class name
     * @param oldSignatureForProxyCheck the old signature for proxy check
     * @throws IOException error working with classDefinition
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static void recreateProxy(ClassLoader classLoader, String archivePath, Map registeredProxiedBeans, String beanClassName,
            String oldSignatureForProxyCheck) throws IOException {

        BeanDeploymentArchiveAgent bdaAgent = BdaAgentRegistry.get(archivePath);

        if (bdaAgent == null) {
            LOGGER.error("Archive path '{}' is not associated with any BeanDeploymentArchiveAgent", archivePath);
            return;
        }

        try {
            // BDA classLoader can be different then appClassLoader for Wildfly/EAR deployment
            // therefore we use class loader from BdaAgent class which is classloader for BDA
            Class<?> beanClass = bdaAgent.getClass().getClassLoader().loadClass(beanClassName);
            bdaAgent.doRecreateProxy(classLoader, registeredProxiedBeans, beanClass, oldSignatureForProxyCheck);
        } catch (ClassNotFoundException e) {
            LOGGER.error("Bean class not found.", e);
        }
    }

    /**
     * Reload bean (reload bean according strategy, reinject instances). Called from BeanClassRefreshCommand.
     *
     * @param classLoader
     * @param archivePath
     * @param beanClassName
     * @throws IOException error working with classDefinition
     */
    public static void reloadBean(ClassLoader classLoader, String archivePath, String beanClassName, String oldSignatureByStrategy,
            String strReloadStrategy) throws IOException {

        BeanDeploymentArchiveAgent bdaAgent = BdaAgentRegistry.get(archivePath);

        if (bdaAgent == null) {
            LOGGER.error("Archive path '{}' is not associated with any BeanDeploymentArchiveAgent", archivePath);
            return;
        }

        try {

            BeanReloadStrategy reloadStrategy;

            try {
                reloadStrategy = BeanReloadStrategy.valueOf(strReloadStrategy);
            } catch (Exception e) {
                reloadStrategy = BeanReloadStrategy.NEVER;
            }

            // BDA classLoader can be different then appClassLoader for Wildfly/EAR deployment
            // therefore we use class loader from BdaAgent class which is classloader for BDA
            Class<?> beanClass = bdaAgent.getClass().getClassLoader().loadClass(beanClassName);

            bdaAgent.doReloadBean(classLoader, beanClass, oldSignatureByStrategy, reloadStrategy);

        } catch (ClassNotFoundException e) {
            LOGGER.error("Bean class not found.", e);
        } finally {
            reloadFlag = false;
        }
    }

    /**
     * Reload bean in existing bean manager.
     * @param reloadStrategy
     * @param oldSignatureByStrategy
     *
     * @param bdaId the Bean Deployment Archive ID
     * @param beanClassName
     */
    @SuppressWarnings("rawtypes")
    private void doReloadBean(ClassLoader classLoader, Class<?> beanClass, String oldSignatureByStrategy, BeanReloadStrategy reloadStrategy) {

        ClassLoader oldContextClassLoader = Thread.currentThread().getContextClassLoader();

        try {
            Thread.currentThread().setContextClassLoader(classLoader);

            // check if it is Object descendant
            if (Object.class.isAssignableFrom(beanClass)) {

                BeanManagerImpl beanManager;
                if (CDI.current().getBeanManager() instanceof BeanManagerImpl) {
                    beanManager = ((BeanManagerImpl) CDI.current().getBeanManager()).unwrap();
                } else {
                    beanManager = ((BeanManagerProxy) CDI.current().getBeanManager()).unwrap();
                }

                Set<Bean<?>> beans = beanManager.getBeans(beanClass);

                if (beans != null && !beans.isEmpty()) {
                    for (Bean<?> bean : beans) {
                        if (bean instanceof AbstractClassBean) {
                            doReloadAbstractClassBean(beanManager, beanClass, (AbstractClassBean) bean, oldSignatureByStrategy, reloadStrategy);
                        } else {
                            LOGGER.warning("reloadBean() : class '{}' reloading is not implemented ({}).",
                                    bean.getClass().getName(), bean.getBeanClass());
                        }
                    }
                    LOGGER.debug("Bean reloaded '{}'", beanClass.getName());
                } else {
                    try {
                        ClassTransformer classTransformer = getClassTransformer();
                        SlimAnnotatedType<?> annotatedType = classTransformer.getBackedAnnotatedType(beanClass, getBdaId());
                        boolean managedBeanOrDecorator = Beans.isTypeManagedBeanOrDecoratorOrInterceptor(annotatedType);

                        if (managedBeanOrDecorator) {
                            EnhancedAnnotatedType eat = EnhancedAnnotatedTypeImpl.of(annotatedType, classTransformer);
                            defineManagedBean(beanManager, eat);
                            // define managed bean
                            // beanManager.cleanupAfterBoot();
                            LOGGER.debug("Bean defined '{}'", beanClass.getName());
                        } else {
                            // TODO : define session bean
                            LOGGER.warning("Bean NOT? defined '{}', session bean?", beanClass.getName());
                        }
                    } catch (Exception e) {
                        LOGGER.debug("Bean definition failed", e);
                    }
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(oldContextClassLoader);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void doReloadAbstractClassBean(BeanManagerImpl beanManager, Class<?> beanClass, AbstractClassBean bean, String oldSignatureByStrategy,
            BeanReloadStrategy reloadStrategy) {

        EnhancedAnnotatedType eat = createAnnotatedTypeForExistingBeanClass(beanClass);

        if (!eat.isAbstract() || !eat.getJavaClass().isInterface()) { // injectionTargetCannotBeCreatedForInterface

            bean.setProducer(beanManager.getLocalInjectionTargetFactory(eat).createInjectionTarget(eat, bean, false));

            String signatureByStrategy = WeldClassSignatureHelper.getSignatureByStrategy(reloadStrategy, beanClass);

            if (bean instanceof ManagedBean && (
                  reloadStrategy == BeanReloadStrategy.CLASS_CHANGE ||
                 (reloadStrategy != BeanReloadStrategy.NEVER && signatureByStrategy != null && !signatureByStrategy.equals(oldSignatureByStrategy)))
                 ) {
                // Reload bean in contexts - invalidates existing instances
                doReloadManagedBeanInContexts(beanManager, beanClass, (ManagedBean) bean);

            } else {
                // Reinjects bean instances in aproperiate contexts
                doReinjectAbstractClassBeanInstances(beanManager, beanClass, bean);
            }
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void doReinjectAbstractClassBeanInstances(BeanManagerImpl beanManager, Class<?> beanClass, AbstractClassBean bean) {
        // keep beans in contexts, reinitialize bean injection points
        try {
            Map<Class<? extends Annotation>, List<Context>> contexts = getContexts(beanManager);
            List<Context> ctx = contexts.get(bean.getScope());
            if (ctx != null) {
                for (Context context : ctx) {
                    if (context.isActive()) {
                        Object get = context.get(bean);
                        if (get != null) {
                            LOGGER.debug("Bean injection points are reinitialized '{}'", beanClass.getName());
                            bean.getProducer().inject(get, beanManager.createCreationalContext(bean));
                        }
                    } else {
                        context = PassivatingContextWrapper.unwrap(context);
                        if (context.getScope().equals(SessionScoped.class) && context instanceof HttpSessionContextImpl) {
                            doReinjectInSessionCtx(beanManager, beanClass, bean, context);
                        }
                    }
                }
            }

        } catch (org.jboss.weld.context.ContextNotActiveException e) {
            LOGGER.warning("No active contexts for {}", beanClass.getName());
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void doReinjectInSessionCtx(BeanManagerImpl beanManager, Class<?> beanClass, AbstractClassBean bean, Context context) {
        HttpSessionContextImpl sessionContext = (HttpSessionContextImpl) context;
        List<HttpSession> seenSessions = HttpSessionsRegistry.getSeenSessions();
        for (HttpSession session : seenSessions) {
            BeanStore beanStore = null;
            try {
                NamingScheme namingScheme = (NamingScheme) ReflectionHelper.get(sessionContext, "namingScheme");
                beanStore = new EagerSessionBeanStore(namingScheme, session);
                ReflectionHelper.invoke(sessionContext, AbstractBoundContext.class, "setBeanStore", new Class[] {BoundBeanStore.class}, beanStore);
                sessionContext.activate();
                Object get = sessionContext.get(bean);
                if (get != null) {
                    LOGGER.debug("Bean injection points are reinitialized '{}'", beanClass.getName());
                    bean.getProducer().inject(get, beanManager.createCreationalContext(bean));
                }

            } finally {
                try {
                    sessionContext.deactivate();
                } catch (Exception e) {
                }
                if (beanStore != null) {
                    try {
                        ReflectionHelper.invoke(sessionContext, AbstractBoundContext.class, "setBeanStore", new Class[] {BeanStore.class}, (BeanStore) null);
                    } catch (Exception e) {
                    }
                }
            }
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void doReloadManagedBeanInContexts(BeanManagerImpl beanManager, Class<?> beanClass, ManagedBean managedBean) {
        try {
            Map<Class<? extends Annotation>, List<Context>> contexts = getContexts(beanManager);

            List<Context> ctxList = contexts.get(managedBean.getScope());

            if (ctxList != null) {
                for(Context context: ctxList) {
                    if (context != null) {
                        LOGGER.debug("Inspecting context '{}' for bean class {}", context.getClass(), managedBean.getScope());
                        if(ContextualReloadHelper.addToReloadSet(context, managedBean)) {
                            LOGGER.debug("Bean {}, added to reload set in context {}", managedBean, context.getClass());
                        } else {
                            // fallback for not patched contexts
                            doReinjectAbstractClassBeanInstances(beanManager, beanClass, managedBean);
                        }
                    } else {
                        LOGGER.debug("No active contexts for bean: {} in scope: {}",  managedBean.getScope(), beanClass.getName());
                    }
                }
            } else {
                LOGGER.debug("No active contexts for bean: {} in scope: {}",  managedBean.getScope(), beanClass.getName());
            }
        } catch (org.jboss.weld.context.ContextNotActiveException e) {
            LOGGER.warning("No active contexts for {}", e, beanClass.getName());
        } catch (Exception e) {
            LOGGER.warning("Context for {} failed to reload", e, beanClass.getName());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<? extends Annotation>, List<Context>> getContexts(BeanManagerImpl beanManager){
        try {
            return Map.class.cast(ReflectionHelper.get(beanManager, "contexts"));
        } catch (Exception e) {
            LOGGER.warning("BeanManagerImpl.contexts not accessible", e);
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void defineManagedBean(BeanManagerImpl beanManager, EnhancedAnnotatedType eat) throws Exception {
        BeanAttributes attributes = BeanAttributesFactory.forBean(eat, beanManager);
        ManagedBean<?> bean = ManagedBean.of(attributes, eat, beanManager);
        Field field = beanManager.getClass().getDeclaredField("beanSet");
        field.setAccessible(true);
        field.set(beanManager, Collections.synchronizedSet(new HashSet<Bean<?>>()));
        // TODO:
        beanManager.addBean(bean);
        beanManager.getBeanResolver().clear();
        bean.initializeAfterBeanDiscovery();
    }

    private EnhancedAnnotatedType<?> createAnnotatedTypeForExistingBeanClass(Class<?> beanClass) {
        ClassTransformer classTransformer = getClassTransformer();
        SlimAnnotatedType<?> annotatedType = classTransformer.getBackedAnnotatedType(beanClass, getBdaId());
        return EnhancedAnnotatedTypeImpl.of(annotatedType, classTransformer);
    }

    private ClassTransformer getClassTransformer() {
        TypeStore store = new TypeStore();
        SharedObjectCache cache = new SharedObjectCache();
        ReflectionCache reflectionCache = ReflectionCacheFactory.newInstance(store);
        ClassTransformer classTransformer = new ClassTransformer(store, cache, reflectionCache, "STATIC_INSTANCE");
        return classTransformer;
    }

    private void doRecreateProxy(ClassLoader classLoader, Map<Object, Object> registeredProxiedBeans, Class<?> beanClass, String oldClassSignature) {
        if (oldClassSignature != null && registeredProxiedBeans != null) {
            String newClassSignature = WeldClassSignatureHelper.getSignatureForProxyClass(beanClass);
            if (newClassSignature != null && !newClassSignature.equals(oldClassSignature)) {
                synchronized (registeredProxiedBeans) {
                    if (!registeredProxiedBeans.isEmpty()) {
                        doRecreateProxy(classLoader, registeredProxiedBeans, beanClass);
                    }
                }
            }
        }
    }

    private void doRecreateProxy(ClassLoader classLoader, Map<Object, Object> registeredBeans, Class<?> proxyClass) {

        ClassLoader oldContextClassLoader = Thread.currentThread().getContextClassLoader();

        try {

            ProxyClassLoadingDelegate.beginProxyRegeneration();
            Class<?> proxyFactoryClass = null;

            for (Entry<Object, Object> entry : registeredBeans.entrySet()) {
                Bean<?> bean = (Bean<?>) entry.getKey();

                if (bean != null) {
                    Set<Type> types = bean.getTypes();
                    if (types.contains(proxyClass)) {
                        Thread.currentThread().setContextClassLoader(bean.getBeanClass().getClassLoader());
                        if (proxyFactoryClass == null) {
                            proxyFactoryClass = classLoader.loadClass("org.jboss.weld.bean.proxy.ProxyFactory");
                        }
                        Object proxyFactory = entry.getValue();
                        LOGGER.info("Recreate proxyClass {} for bean class {}.", proxyClass.getName(), bean.getClass());
                        ReflectionHelper.invoke(proxyFactory, proxyFactoryClass, "getProxyClass", new Class[] {});
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.error("recreateProxyFactory() exception {}.", e, e.getMessage());
        } finally {
            Thread.currentThread().setContextClassLoader(oldContextClassLoader);
            ProxyClassLoadingDelegate.endProxyRegeneration();
        }
    }

}
