package org.hotswap.agent.plugin.owb.command;

import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.enterprise.context.ContextNotActiveException;
import javax.enterprise.context.Dependent;
import javax.enterprise.context.RequestScoped;
import javax.enterprise.context.SessionScoped;
import javax.enterprise.context.spi.Context;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.CDI;
import javax.enterprise.inject.spi.InjectionTarget;
import javax.enterprise.inject.spi.InjectionTargetFactory;

import org.apache.webbeans.component.BeanAttributesImpl;
import org.apache.webbeans.component.InjectionTargetBean;
import org.apache.webbeans.component.creation.BeanAttributesBuilder;
import org.apache.webbeans.config.WebBeansContext;
import org.apache.webbeans.container.BeanManagerImpl;
import org.apache.webbeans.container.InjectableBeanManager;
import org.apache.webbeans.container.InjectionTargetFactoryImpl;
import org.apache.webbeans.portable.AnnotatedElementFactory;
import org.apache.webbeans.spi.BeanArchiveService.BeanArchiveInformation;
import org.apache.webbeans.spi.ContextsService;
import org.apache.webbeans.web.context.WebContextsService;
import org.hotswap.agent.logging.AgentLogger;
import org.hotswap.agent.logging.AgentLogger.Level;
import org.hotswap.agent.plugin.owb.BeanReloadStrategy;
import org.hotswap.agent.plugin.owb.OwbClassSignatureHelper;
import org.hotswap.agent.plugin.owb.beans.ContextualReloadHelper;
import org.hotswap.agent.plugin.owb.transformer.CdiContextsTransformer;
import org.hotswap.agent.plugin.owb.transformer.WebBeansContextsServiceTransformer;
import org.hotswap.agent.util.ReflectionHelper;

/**
 * Handles definition and redefinition of bean classes in BeanManager. If the bean class already exists than, according reloading policy,
 * either bean instance re-injection or bean context reloading is processed.
 *
 * @author Vladimir Dvorak
 */
public class BeanClassRefreshAgent {

    private static AgentLogger LOGGER = AgentLogger.getLogger(BeanClassRefreshAgent.class);

    /**
     * Flag for checking reload status. It is used in unit tests for waiting for reload finish.
     * Set flag to true in the unit test class and wait until the flag is false again.
     */
    public static boolean reloadFlag = false;

    /**
     * Called by a reflection command from BeanRefreshCommand transformer.
     *
     * @param appClassLoader the application class loader
     * @param beanClassName the bean class name
     * @param oldSignatureByStrategy the old signature by strategy
     * @param strReloadStrategy the bean reload strategy
     * @param beanArchiveUrl the bean archive url
     * @throws IOException error working with classDefinition
     */
    public static void reloadBean(ClassLoader appClassLoader, String beanClassName, String oldSignatureByStrategy,
            String strReloadStrategy, URL beanArchiveUrl) throws IOException {

        try {
            BeanReloadStrategy reloadStrategy;

            try {
                reloadStrategy = BeanReloadStrategy.valueOf(strReloadStrategy);
            } catch (Exception e) {
                reloadStrategy = BeanReloadStrategy.NEVER;
            }

            Class<?> beanClass = appClassLoader.loadClass(beanClassName);
            doReloadBean(appClassLoader, beanClass, oldSignatureByStrategy, reloadStrategy, beanArchiveUrl);

        } catch (ClassNotFoundException e) {
            LOGGER.error("Bean class '{}' not found.", e, beanClassName);
        } finally {
            reloadFlag = false;
        }
    }

    /**
     * Reload bean in existing bean manager.
     *
     * @param appClassLoader the class loader
     * @param beanClass the bean class
     * @param oldSignatureByStrategy the old signature by strategy
     * @param reloadStrategy the reload strategy
     * @param beansXml the path to beans.xml
     */
    @SuppressWarnings("rawtypes")
    private static void doReloadBean(ClassLoader appClassLoader, Class<?> beanClass, String oldSignatureByStrategy,
            BeanReloadStrategy reloadStrategy, URL beanArchiveUrl) {

        ClassLoader oldContextClassLoader = Thread.currentThread().getContextClassLoader();

        try {
            Thread.currentThread().setContextClassLoader(appClassLoader);

            // check if it is Object descendant
            if (Object.class.isAssignableFrom(beanClass)) {

                BeanManagerImpl beanManager = null;
                BeanManager bm = CDI.current().getBeanManager();

                if (bm instanceof BeanManagerImpl) {
                    beanManager = (BeanManagerImpl) bm;
                } else if (bm instanceof InjectableBeanManager){
                    beanManager = (BeanManagerImpl) ReflectionHelper.get(bm, "bm");
                }

                BeanArchiveInformation beanArchiveInfo =
                        beanManager.getWebBeansContext().getBeanArchiveService().getBeanArchiveInformation(beanArchiveUrl);

                if (!beanArchiveInfo.isClassExcluded(beanClass.getName())) {

                    Set<Bean<?>> beans = beanManager.getBeans(beanClass);

                    if (beans != null && !beans.isEmpty()) {
                        boolean failed = false;
                        for (Bean<?> bean : beans) {
                            // just now only managed beans
                            if (bean instanceof InjectionTargetBean) {
                                createAnnotatedTypeForExistingBeanClass(beanManager, beanClass, (InjectionTargetBean) bean);
                                if (isReinjectingContext(bean)) {
                                  doReloadInjectionTargetBean(beanManager, beanClass, (InjectionTargetBean) bean,
                                          oldSignatureByStrategy, reloadStrategy);
                                }
                            } else {
                                LOGGER.warning("Bean '{}' reloading failed. Bean is not InjectionTargetBean.", bean.getBeanClass());
                                failed = true;
                            }
                        }
                        if (!failed) {
                            LOGGER.debug("Bean '{}' was reloaded.", beanClass.getName());
                        }
                    } else {
                        // Define new bean
                        HaBeanDefiner.doDefineManagedBean(beanManager, beanClass);
                    }
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(oldContextClassLoader);
        }
    }

    @SuppressWarnings("rawtypes")
    private static void doReloadInjectionTargetBean(BeanManagerImpl beanManager, Class<?> beanClass, InjectionTargetBean bean,
            String oldSignatureByStrategy, BeanReloadStrategy reloadStrategy) {

        String signatureByStrategy = OwbClassSignatureHelper.getSignatureByStrategy(reloadStrategy, beanClass);

        if (reloadStrategy == BeanReloadStrategy.CLASS_CHANGE ||
                (reloadStrategy != BeanReloadStrategy.NEVER && signatureByStrategy != null && !signatureByStrategy.equals(oldSignatureByStrategy))) {
            // Reload bean in contexts - invalidates existing instances
            doReloadBean(beanManager, beanClass, bean);
        } else {
            // keep beans in contexts, reinitialize bean injection points
            doReinjectBean(beanManager, beanClass, bean);
        }
    }

    private static boolean isReinjectingContext(Bean<?> bean) {
        return bean.getScope() != RequestScoped.class && bean.getScope() != Dependent.class;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void createAnnotatedTypeForExistingBeanClass(BeanManagerImpl beanManager, Class<?> beanClass, InjectionTargetBean bean) {

        WebBeansContext wbc = beanManager.getWebBeansContext();

        AnnotatedElementFactory annotatedElementFactory = wbc.getAnnotatedElementFactory();
        // Clear AnnotatedElementFactory caches
        annotatedElementFactory.clear();

        AnnotatedType annotatedType = annotatedElementFactory.newAnnotatedType(beanClass);

        ReflectionHelper.set(bean, InjectionTargetBean.class, "annotatedType", annotatedType);

        // Updated members that were set by bean attributes
        BeanAttributesImpl attributes = BeanAttributesBuilder.forContext(wbc).newBeanAttibutes(annotatedType).build();
        ReflectionHelper.set(bean, BeanAttributesImpl.class, "types", attributes.getTypes());
        ReflectionHelper.set(bean, BeanAttributesImpl.class, "qualifiers", attributes.getQualifiers());
        ReflectionHelper.set(bean, BeanAttributesImpl.class, "scope", attributes.getScope());
        ReflectionHelper.set(bean, BeanAttributesImpl.class, "name", attributes.getName());
        ReflectionHelper.set(bean, BeanAttributesImpl.class, "stereotypes", attributes.getStereotypes());
        ReflectionHelper.set(bean, BeanAttributesImpl.class, "alternative", attributes.isAlternative());

        InjectionTargetFactory factory = new InjectionTargetFactoryImpl(annotatedType, bean.getWebBeansContext());
        InjectionTarget injectionTarget = factory.createInjectionTarget(bean);
        ReflectionHelper.set(bean, InjectionTargetBean.class, "injectionTarget", injectionTarget);

        LOGGER.debug("New annotated type created for bean '{}'", beanClass.getName());
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void doReinjectBean(BeanManagerImpl beanManager, Class<?> beanClass, InjectionTargetBean bean) {
        try {
            WebBeansContext wbc = beanManager.getWebBeansContext();
            ContextsService contextsService = wbc.getContextsService();

            if (contextsService instanceof WebContextsService) {
                if (isTrackableSessionBasedScope(bean.getScope())) {
                    doReinjectSessionScopeBasedBean(beanManager, beanClass, bean, contextsService);
                } else {
                    doReinjectBeanInstance(beanManager, beanClass, bean, beanManager.getContext(bean.getScope()));
                }
            } else {
                doReinjectBeanInstance(beanManager, beanClass, bean, beanManager.getContext(bean.getScope()));
            }
        } catch (ContextNotActiveException e) {
            LOGGER.info("No active contexts for bean '{}'", beanClass.getName());
        }
    }

    private static boolean isTrackableSessionBasedScope(Class<?> scope) {
        if (scope == SessionScoped.class) {
            return true;
        }
        // TODO : find out another way how to mark the scope is trackable from HA
        if ("org.apache.deltaspike.core.api.scope.WindowScoped".equals(scope.getName())) {
            return true;
        }
        return false;
    }

    @SuppressWarnings("rawtypes")
    private static void doReinjectSessionScopeBasedBean(BeanManagerImpl beanManager, Class<?> beanClass, InjectionTargetBean bean,
            ContextsService contextsService) {

        // SessionContextsTracker can't be directly used here, since it can be in different class loaders (Tomee)
        // therefore inner Iterator is used as workaround
        Object sessionContextsTracker =
                ReflectionHelper.get(contextsService, WebBeansContextsServiceTransformer.SESSION_CONTEXTS_TRACKER_FIELD);

        if (sessionContextsTracker != null) {
            try {
                Iterator sessionContextIterator = ((Iterable ) sessionContextsTracker).iterator();
                while (sessionContextIterator.hasNext()) {
                    sessionContextIterator.next(); // Set next active session context
                    Context sessionContext = beanManager.getContext(SessionScoped.class);
                    if (bean.getScope() != SessionScoped.class) {
                        doReinjectCustomScopedBean(beanManager, beanClass, bean, sessionContext);
                    } else {
                        doReinjectBeanInstance(beanManager, beanClass, bean, sessionContext);
                    }
                }
            } finally {
                contextsService.removeThreadLocals();
            }
        } else {
            LOGGER.error("Field {} not found in class '{}'", WebBeansContextsServiceTransformer.SESSION_CONTEXTS_TRACKER_FIELD,
                    contextsService.getClass().getName());
        }
    }

    private static void doReinjectCustomScopedBean(BeanManagerImpl beanManager, Class<?> beanClass, InjectionTargetBean bean,
            Context sessionContext) {

        // Get custom context tracker from map stored in session context
        Map trackerMap = (Map) ReflectionHelper.get(sessionContext, CdiContextsTransformer.CUSTOM_CONTEXT_TRACKER_FIELD);
        if (trackerMap == null) {
            LOGGER.error("Custom context tracker field '{}' not found in session context.", CdiContextsTransformer.CUSTOM_CONTEXT_TRACKER_FIELD);
            return;
        }

        Class beanScope = bean.getScope();
        Object customContextTracker = trackerMap.get(beanScope.getName());

        if (customContextTracker == null) {
            LOGGER.debug("Custom context tracker for scope '{}' not found.", beanScope.getName());
            return;
        }
        if (! (customContextTracker instanceof Iterable)) {
            LOGGER.error("Tracker '{}' is not Iterable.", customContextTracker.getClass().getName());
            return;
        }

        Iterator contextIterator = ((Iterable) customContextTracker).iterator();
        try {
            while (contextIterator.hasNext()) {
                contextIterator.next(); // Set active session context
                Context customContext = beanManager.getContext(beanScope);
                doReinjectBeanInstance(beanManager, beanClass, bean, customContext);
            }
        } finally {
            // iterator can implement closeable to finalize iteration
            if (contextIterator instanceof Closeable) {
                try {
                    ((Closeable) contextIterator).close();
                } catch (Exception e) {
                    LOGGER.error("Context iterator close() failed.", e);
                }
            }
        }
    }

    private static void doReloadBean(BeanManagerImpl beanManager, Class<?> beanClass, InjectionTargetBean bean) {
        try {
            Map<Class<? extends Annotation>, Context> singleContextMap = getSingleContextMap(beanManager);

            Context context = singleContextMap.get(bean.getScope());
            if (context != null) {
                doReloadBeanInContext(beanManager, beanClass, bean, context);
            } else {
                Map<Class<? extends Annotation>, List<Context>> allContexts = getContextMap(beanManager);
                List<Context> ctxList = allContexts.get(bean.getScope());
                if (ctxList != null) {
                    for(Context ctx: ctxList) {
                        doReloadBeanInContext(beanManager, beanClass, bean, ctx);
                    }
                } else {
                    LOGGER.debug("No active contexts for bean '{}', bean scope '{}'",  bean.getScope(), beanClass.getName());
                    return;
                }
            }

        } catch (Exception e) {
            LOGGER.warning("Context for '{}' failed to reload", e, beanClass.getName());
        }
    }

    @SuppressWarnings("unchecked")
    private static void doReinjectBeanInstance(BeanManagerImpl beanManager, Class<?> beanClass, InjectionTargetBean bean, Context context) {
        Object instance = context.get(bean);
        if (instance != null) {
            bean.getProducer().inject(instance, beanManager.createCreationalContext(bean));
            LOGGER.info("Bean '{}' injection points was reinjected.", beanClass.getName());
        }
    }

    private static void doReloadBeanInContext(BeanManagerImpl beanManager, Class<?> beanClass, InjectionTargetBean bean, Context context) {
        if (ContextualReloadHelper.addToReloadSet(context, bean)) {
            LOGGER.debug("Bean {}, added to reload set in context '{}'", bean, context.getClass());
        } else {
            // fallback: try to reinitialize injection points instead...
            try {
                doReinjectBeanInstance(beanManager, beanClass, bean, context);
            } catch (Exception e) {
                if(LOGGER.isLevelEnabled(Level.DEBUG)) {
                    LOGGER.debug("Context '{}' not active. Bean '{}', bean scope '{}'",e, context.getClass(), beanClass.getName(), bean.getScope());
                } else {
                    LOGGER.warning("Context '{}' not active. Bean '{}', bean scope '{}'", context.getClass(), beanClass.getName(), bean.getScope());
                }
            }
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Map<Class<? extends Annotation>, List<Context>> getContextMap(BeanManagerImpl beanManagerImpl){
        try {
            Field contextsField = BeanManagerImpl.class.getField("contextMap");
            contextsField.setAccessible(true);
            return (Map) contextsField.get(beanManagerImpl);
        } catch (IllegalAccessException |IllegalArgumentException | NoSuchFieldException | SecurityException e) {
            LOGGER.warning("Field BeanManagerImpl.contextMap is not accessible", e);
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Map<Class<? extends Annotation>, Context> getSingleContextMap(BeanManagerImpl beanManagerImpl){
        try {
            Field contextsField = BeanManagerImpl.class.getField("singleContextMap");
            contextsField.setAccessible(true);
            return (Map) contextsField.get(beanManagerImpl);
        } catch (IllegalAccessException |IllegalArgumentException | NoSuchFieldException | SecurityException e) {
            LOGGER.warning("Field BeanManagerImpl.singleContextMap is not accessible", e);
        }
        return Collections.emptyMap();
    }

}