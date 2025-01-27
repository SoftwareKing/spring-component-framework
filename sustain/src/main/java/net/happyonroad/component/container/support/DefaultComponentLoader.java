/**
 * @author XiongJie, Date: 13-9-22
 */
package net.happyonroad.component.container.support;

import net.happyonroad.component.container.ComponentLoader;
import net.happyonroad.component.container.ComponentRepository;
import net.happyonroad.component.container.ServiceRegistry;
import net.happyonroad.component.container.feature.ApplicationFeatureResolver;
import net.happyonroad.component.container.feature.ServiceFeatureResolver;
import net.happyonroad.component.container.feature.StaticFeatureResolver;
import net.happyonroad.component.core.*;
import net.happyonroad.component.core.Component;
import net.happyonroad.component.core.exception.DependencyNotMeetException;
import net.happyonroad.component.core.exception.InvalidComponentNameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 组件加载
 */
public class DefaultComponentLoader implements ComponentLoader, ComponentContext {
    private Logger logger = LoggerFactory.getLogger(DefaultComponentLoader.class);

    /*package*/ final List<FeatureResolver>    featureResolvers;
    /*package*/ final List<FeatureResolver>    reverseResolvers;
    /*package*/ final Map<Component, Features> loadedFeatures;
    /*package*/ final ServiceRegistry          registry;
    /*package*/ final ComponentRepository      repository;
    /*package*/ final Set<Component> loading, unloading;


    public DefaultComponentLoader(ComponentRepository repository,
                                  FeatureResolver... resolvers) {
        this.repository = repository;
        loadedFeatures = new ConcurrentHashMap<Component, Features>();
        registry = new DefaultServiceRegistry();
        featureResolvers = new LinkedList<FeatureResolver>();
        reverseResolvers = new LinkedList<FeatureResolver>();
        loading = new LinkedHashSet<Component>();
        unloading = new LinkedHashSet<Component>();
        //注册缺省的特性解析器
        registerResolver(new StaticFeatureResolver());
        registerResolver(new ApplicationFeatureResolver());
        registerResolver(new ServiceFeatureResolver());
        //注册构造时传入的扩展Feature Resolvers
        for (FeatureResolver resolver : resolvers) {
            registerResolver(resolver);
        }
    }

    /**
     * 注册一个扩展特性解析器
     *
     * @param resolver 解析器
     */
    public void registerResolver(FeatureResolver resolver) {
        resolver.bind(this);
        featureResolvers.add(resolver);
        reverseResolvers.add(resolver);
        Collections.sort(featureResolvers, new LoadOrder());
        Collections.sort(reverseResolvers, new UnloadOrder());
    }

    @Override
    public <T extends FeatureResolver> T getFeatureResolver(String name) {
        for (FeatureResolver resolver : featureResolvers) {
            if (resolver.getName().equals(name))
                //noinspection unchecked
                return (T) resolver;
        }
        return null;
    }

    @Override
    public boolean isLoaded(Component component) {
        return loadedFeatures.containsKey(component);
    }

    @Override
    public <T> T getFeature(Component component, String name) {
        Features features = loadedFeatures.get(component);
        if (features == null) return null;
        return features.getFeature(name);
    }

    @Override
    public ClassLoader getLibraryFeature(Component component) {
        Features features = loadedFeatures.get(component);
        if (features == null) return null;
        return features.getLibraryFeature();
    }

    @Override
    public ApplicationContext getApplicationFeature(Component component) {
        Features features = loadedFeatures.get(component);
        if (features == null) return null;
        return features.getApplicationFeature();
    }

    @Override
    public List<ApplicationContext> getApplicationFeatures() {
        List<Component> components = new LinkedList<Component>();
        for (Map.Entry<Component, Features> entry : loadedFeatures.entrySet()) {
            if( entry.getValue().getApplicationFeature() != null ){
                components.add(entry.getKey());
            }
        }
        repository.sortComponents(components);
        List<ApplicationContext> contexts = new LinkedList<ApplicationContext>();
        for (Component component : components) {
            ApplicationContext feature = getApplicationFeature(component);
            contexts.add(feature);
        }
        return contexts;
    }

    @Override
    public ApplicationContext getServiceFeature(Component component) {
        Features features = loadedFeatures.get(component);
        if (features == null) return null;
        return features.getServiceFeature();
    }

    @Override
    public ClassLoader getClassRealm(String componentId) {
        try {
            return repository.resolveComponent(componentId).getClassLoader();
        } catch (DependencyNotMeetException e) {
            throw new IllegalArgumentException("The component " + componentId + " is not exist", e);
        } catch (InvalidComponentNameException e) {
            throw new IllegalArgumentException("The component " + componentId + " is invalid", e);
        }
    }

    @Override
    public ApplicationContext getMainApp() {
        String id = repository.getMainComponentId();
        Component component;
        try {
            component = repository.resolveComponent(id);
        } catch (Exception e) {
            throw new RuntimeException("The system is not read for access main app");
        }
        return component.getApplication();
    }

    @Override
    public ServiceRegistry getRegistry() {
        return registry;
    }

    @Override
    public ComponentLoader getComponentLoader() {
        return this;
    }

    @Override
    public ComponentRepository getComponentRepository() {
        return repository;
    }

    /**
     * 自顶向下的加载组件
     *
     * @param component 被加载的组件
     */
    @Override
    public void load(Component component) throws Exception {
        if (isLoaded(component)) return;
        logger.debug("Loading {}", component);
        //首先加载父组件
        if (component.getParent() != null) {
            //上面会自动检测是否已经加载，防止重复加载
            load(component.getParent());
        }
        //而后加载依赖的组件
        for (Component depended : component.getDependedComponents()) {
            //上面会自动检测是否已经加载，防止重复加载
            load(depended);
        }
        loadSingle(component);
        //最后，加载自身
        logger.debug("Loaded  {}", component);
    }

    /**
     * 剥洋葱一样的卸载组件
     *
     * @param component 被卸载的组件
     */
    @Override
    public void unload(Component component) {
        if (!isLoaded(component))  return;
        logger.debug("Unloading {}", component);
        //先卸载自身
        unloadSingle(component);
        //再卸载依赖
        for (Component depended : component.getDependedComponents()) {
            //上面会自动检测是否已经加载，防止重复加载
            unload(depended);
        }
        //最后卸载其父组件
        if (component.getParent() != null) {
            //上面会自动检测是否已经加载，防止重复加载
            unload(component.getParent());
        }
        logger.debug("Unloaded  {}", component);
    }

    boolean isLoading(Component component){
        return loading.contains(component);
    }

    boolean isUnloading(Component component){
        return unloading.contains(component);
    }

    void loading(Component component){
        loading.add(component);
    }

    void unloading(Component component){
        unloading.add(component);
    }

    void loaded(Component component){
        loading.remove(component);
    }

    void unloaded(Component component){
        unloading.remove(component);
    }

    @Override
    public void registerFeature(Component component, String name, Object feature) {
        Features features = loadedFeatures.get(component);
        if (features == null) {
            features = new Features(name, feature);
            loadedFeatures.put(component, features);
        } else {
            features.setFeature(name, feature);
        }
    }

    @Override
    public Object removeFeature(Component component, String name) {
        Features features = loadedFeatures.get(component);
        if (features != null) {
            return features.remove(name);
        }
        return null;
    }

    /**
     * 实际卸载一个组件，不考虑依赖情况
     *
     * @param component 被加载的组件
     * @throws IOException 加载过程中的IO错误
     */
    protected void loadSingle(Component component) throws Exception {
        if (component.isAggregating()) {
            logger.trace("Needn't real load aggregating component {}", component);
            loadedFeatures.put(component, FeatureResolver.AggregatingFlag);
        } else if (component.isPlain()) {
            logger.trace("Needn't real load plain component {}", component);
            loadedFeatures.put(component, FeatureResolver.PlainFlag);
        } else {
            if (isLoading(component)) return;
            loading(component);
            ComponentResource resource = component.getResource();
            if (resource == null) {
                throw new IOException("The component " + component + " without resource");
            }
            logger.trace("Actual loading {}", component);
            List<FeatureResolver> resolvers = new ArrayList<FeatureResolver>(featureResolvers.size());
            for (FeatureResolver featureResolver : featureResolvers) {
                featureResolver.applyDefaults(component);
                if (featureResolver.hasFeature(component)) {
                    resolvers.add(featureResolver);
                }
            }

            for (FeatureResolver featureResolver : resolvers) {
                featureResolver.beforeResolve(component);
            }
            for (FeatureResolver featureResolver : resolvers) {
                featureResolver.resolve(component);
            }
            for (FeatureResolver featureResolver : resolvers) {
                featureResolver.afterResolve(component);
            }
            loaded(component);
        }
    }

    /**
     * 实际卸载一个组件
     *
     * @param component 被卸载的组件
     */
    public void unloadSingle(Component component) {
        if (component.isAggregating()) {
            logger.trace("Remove aggregating component {}", component);
            loadedFeatures.remove(component);
        }else if (component.isPlain()){
            logger.trace("Remove plain component {}", component);
            loadedFeatures.remove(component);
        }else{
            if (isUnloading(component)) return;
            unloading(component);
            logger.trace("Actual unloading {}", component);
            for (FeatureResolver resolver : reverseResolvers) {
                if (resolver.hasFeature(component)) {
                    resolver.release(component);
                }
            }
            loadedFeatures.remove(component);
            unloaded(component);
        }
    }

    static class LoadOrder implements java.util.Comparator<FeatureResolver> {
        @Override
        public int compare(FeatureResolver resolver1, FeatureResolver resolver2) {
            return resolver1.getLoadOrder() - resolver2.getLoadOrder();
        }
    }

    static class UnloadOrder implements java.util.Comparator<FeatureResolver> {
        @Override
        public int compare(FeatureResolver resolver1, FeatureResolver resolver2) {
            return resolver1.getUnloadOrder() - resolver2.getUnloadOrder();
        }
    }
}
