package com.bergerkiller.bukkit.common.server.test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.bergerkiller.bukkit.common.internal.CommonBootstrap;
import org.bukkit.Bukkit;

import com.bergerkiller.bukkit.common.server.CommonServerBase;
import com.bergerkiller.bukkit.common.utils.LogicUtil;
import com.bergerkiller.mountiplex.MountiplexUtil;
import com.bergerkiller.mountiplex.reflection.ClassTemplate;
import com.bergerkiller.mountiplex.reflection.resolver.Resolver;
import com.bergerkiller.mountiplex.reflection.util.NullInstantiator;

class TestServerFactory_1_19_3 extends TestServerFactory {

    private static Class<?> loadClass(String... names) throws ClassNotFoundException {
        ClassNotFoundException lastError = null;
        for (String name : names) {
            try {
                return Class.forName(name, false, TestServerFactory_1_19_3.class.getClassLoader());
            } catch (ClassNotFoundException ex) {
                lastError = ex;
            }
        }
        throw lastError;
    }

    @Override
    protected void init(ServerEnvironment env) throws Throwable {
        final Class<?> systemUtilsClass = loadClass("net.minecraft.SystemUtils", "net.minecraft.Util");
        final Class<?> dataFixerRegistryClass = loadClass(
                "net.minecraft.util.datafix.DataConverterRegistry",
                "net.minecraft.util.datafix.DataFixers"
        );
        final Class<?> bootstrapClass = loadClass(
                "net.minecraft.server.DispenserRegistry",
                "net.minecraft.server.Bootstrap"
        );

        // Initialize shared constants first - required by DispenserRegistry and DataConverterRegistry
        Class<?> sharedConstantsClass = Class.forName("net.minecraft.SharedConstants");
        try {
            Class<?> detectedVersionClass = Class.forName("net.minecraft.DetectedVersion");
            Object builtInVersion = detectedVersionClass.getDeclaredField("BUILT_IN").get(null);
            Method setVersionMethod = Resolver.resolveAndGetDeclaredMethod(sharedConstantsClass, "setVersion",
                    Class.forName("net.minecraft.WorldVersion"));
            setVersionMethod.invoke(null, builtInVersion);
        } catch (Throwable t) {
            Method initSharedConstantsMethod = Resolver.resolveAndGetDeclaredMethod(sharedConstantsClass, "tryDetectVersion");
            initSharedConstantsMethod.invoke(null);
        }

        // Initialize the Data Converter Registry in such a way that no datafixers are registered at all
        // We don't need that trash during the tests we run - it slows it down by way too much
        // This is done by temporarily hacking the bootstrapExecutor to never run tasks - this allows
        // the build() method to return instantly.
        try (BackgroundWorkerDefuser defuser = BackgroundWorkerDefuser.start(systemUtilsClass)) {
            dataFixerRegistryClass.getName();
        }

        // Bootstrap is required
        Method dispenserRegistryBootstrapMethod = Resolver.resolveAndGetDeclaredMethod(bootstrapClass, "bootStrap");
        dispenserRegistryBootstrapMethod.invoke(null);

        try {
            Class<?> entityType = Class.forName("net.minecraft.world.entity.Entity");
            Field scriptEngineField = entityType.getDeclaredField("scriptEngine");
            scriptEngineField.setAccessible(true);
            if (scriptEngineField.get(null) == null) {
                scriptEngineField.set(null, new DummyScriptEngine());
            }
        } catch (Throwable ignored) {}

        // Create some stuff by null-constructing them (not calling initializer)
        // This prevents loads of extra server logic executing during test
        ClassTemplate<?> server_t = ClassTemplate.create(CommonServerBase.SERVER_CLASS);
        Object server = server_t.newInstanceNull();
        Class<?> minecraftServerType = Class.forName("net.minecraft.server.MinecraftServer");
        Class<?> dedicatedType = Class.forName("net.minecraft.server.dedicated.DedicatedServer");
        ClassTemplate<?> mc_server_t = ClassTemplate.create(dedicatedType);
        Object mc_server = mc_server_t.newInstanceNull();

        // Since we null-construct, some members of the parent class "IAsyncTaskHandler" are not initialized. Do that here.
        Class<?> iAsyncTaskHandlerClass = loadClass(
                "net.minecraft.util.thread.IAsyncTaskHandler",
                "net.minecraft.util.thread.BlockableEventLoop"
        );
        setField(mc_server, iAsyncTaskHandlerClass, "name", "Server");
        setField(mc_server, iAsyncTaskHandlerClass, "pendingRunnables", createFromCode(minecraftServerType, 
                "return com.google.common.collect.Queues.newConcurrentLinkedQueue();"));

        // Assign logger, nms Server instance and primary thread (current thread) to avoid NPE's during test
        setField(server, "logger",  MountiplexUtil.LOGGER);
        setField(server, "console", mc_server);
        setField(mc_server, "serverThread", Thread.currentThread());
        setField(null, minecraftServerType, "SERVER", mc_server);

        // Initialize the 'registries' cache field. Added in Bukkit 1.19.1
        try {
            setField(server, "registries", new HashMap<Object, Object>());
        } catch (RuntimeException ex) {}

        // Resource Manager with only the vanilla data pack loaded in
        initVanillaResourceManager(env, minecraftServerType);

        // Use the resource manager to initialize the registries, assign field
        Object registries = initRegistries(env, minecraftServerType);
        setField(mc_server, "registries", registries);

        /*
        // Initialize the dimension root registry for the server
        // this.f = iregistrycustom_dimension; (MinecraftServer.java)
        Object customRegistryDimension = initCustomRegistryDimension(minecraftServerType);
        setField(mc_server, "registryHolder", customRegistryDimension);
        */

        // Assign to the Bukkit server silently (don't want a duplicate server info log line with random null's)
        Field bkServerField = Bukkit.class.getDeclaredField("server");
        bkServerField.setAccessible(true);
        bkServerField.set(null, server);

        // Initialize propertyManager field, which is responsible for server-wide settings like view distance
        Object propertyManager = ClassTemplate.create("net.minecraft.server.dedicated.DedicatedServerSettings").newInstanceNull();
        setField(mc_server, "settings", propertyManager);
        setField(propertyManager, "properties", createFromCode(Class.forName("net.minecraft.server.dedicated.DedicatedServerProperties"),
                "return new DedicatedServerProperties(new java.util.Properties(), new joptsimple.OptionParser().parse(new String[0]));\n"));

        // Create data converter registry manager object - used for serialization/deserialization
        Class<?> dataConverterRegistryClass = dataFixerRegistryClass;
        Method dataConverterRegistryInitMethod = Resolver.resolveAndGetDeclaredMethod(dataConverterRegistryClass, "getDataFixer");
        Object dataConverterManager = dataConverterRegistryInitMethod.invoke(null);
        setField(mc_server, "fixerUpper", dataConverterManager);

        // this.executorService = SystemUtils.e();
        {
            setField(mc_server, "executor", createFromCode(minecraftServerType,
                    "return " + systemUtilsClass.getName() + ".backgroundExecutor();"));
        }

        // ResourcePack initialization (makes recipes available)
        initDataPack(env, minecraftServerType, mc_server, registries);

        // Set server worldData field, required by the Entity constructors under test (EntityType)
        // WorldData enabledFeatures() should return non-null to avoid trouble
        // To do this, set a valid WorldSettings in the SavedData worldData field
        // The WorldSettings must have a valid WorldDataConfiguration
        // The WorldDataConfiguration must have the feature set
        // Damn mojang, so many nested classes!
        {
            Object worldDataConfiguration = createFromCode(Class.forName("net.minecraft.world.level.WorldDataConfiguration"),
                                                           "return WorldDataConfiguration.DEFAULT;");

            Object worldSettings = NullInstantiator.of(loadClass(
                    "net.minecraft.world.level.WorldSettings",
                    "net.minecraft.world.level.LevelSettings"
            )).create();
            setField(worldSettings, "dataConfiguration", worldDataConfiguration);

            Object worldData = NullInstantiator.of(loadClass(
                    "net.minecraft.world.level.storage.WorldDataServer",
                    "net.minecraft.world.level.storage.PrimaryLevelData"
            )).create();
            setField(worldData, "settings", worldSettings);
            setField(mc_server, "worldData", worldData);
        }
    }

    protected Object initCustomRegistryDimension(Class<?> minecraftServerType) {
        return createFromCode(minecraftServerType, "return net.minecraft.core.IRegistryCustom.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);");
    }

    protected void initVanillaResourceManager(ServerEnvironment env, Class<?> minecraftServerType) throws Throwable {
        if (CommonBootstrap.evaluateMCVersion(">=", "1.19.4")) {
            Class<?> packRepositoryType = Class.forName("net.minecraft.server.packs.repository.PackRepository");
            Class<?> serverPacksSourceType = Class.forName("net.minecraft.server.packs.repository.ServerPacksSource");
            Object resourcepackrepository = Resolver.resolveAndGetDeclaredMethod(serverPacksSourceType,
                    "createPackRepository", java.nio.file.Path.class)
                    .invoke(null, java.nio.file.Path.of("."));

            Resolver.resolveAndGetDeclaredMethod(packRepositoryType, "reload").invoke(resourcepackrepository);
            Resolver.resolveAndGetDeclaredMethod(packRepositoryType, "setSelected", java.util.Collection.class)
                    .invoke(resourcepackrepository, java.util.Collections.singleton("vanilla"));

            Object resourcepacktype = getStaticField(Class.forName("net.minecraft.server.packs.PackType"), "SERVER_DATA");
            java.util.List<?> packs = (java.util.List<?>) Resolver.resolveAndGetDeclaredMethod(packRepositoryType, "openAllSelected")
                    .invoke(resourcepackrepository);
            Object resourcemanager = construct(Class.forName("net.minecraft.server.packs.resources.ReloadableResourceManager"),
                    resourcepacktype);
            Object unitInstance = getStaticField(Class.forName("net.minecraft.util.Unit"), "INSTANCE");
            CompletableFuture<?> initialReload = CompletableFuture.completedFuture(unitInstance);
            Resolver.resolveAndGetDeclaredMethod(resourcemanager.getClass(), "createReload",
                    Executor.class, Executor.class, CompletableFuture.class, java.util.List.class)
                    .invoke(resourcemanager, newThreadExecutor(), newThreadExecutor(), initialReload, packs);

            env.resourcePackRepository = resourcepackrepository;
            env.resourceManager = resourcemanager;
            return;
        }

        final String repopath = "net.minecraft.server.packs.repository.";
        final Class<?> resourcePackRepositoryType = Class.forName(repopath + "ResourcePackRepository");

        /*
         * Create ResourcePackRepository (Main.java)
         * 
         * ResourcePackRepository resourcepackrepository = new ResourcePackRepository(
         *         EnumResourcePackType.SERVER_DATA, new ResourcePackSource[]{new ResourcePackSourceVanilla()});
         */
        Object resourcepackrepository;
        Object resourcepacktype;
        {
            // arg0: EnumResourcePackType
            Class<?> enumSourcePackTypeClass = Class.forName("net.minecraft.server.packs.EnumResourcePackType");
            resourcepacktype = getStaticField(enumSourcePackTypeClass, "SERVER_DATA");

            // arg1: ResourcePackSource[]
            final Object[] resourcePackSources = LogicUtil.createArray(Class.forName(repopath + "ResourcePackSource"), 1);
            resourcePackSources[0] = construct(Class.forName(repopath + "ResourcePackSourceVanilla"));

            // Construct new ResourcePackRepository
            resourcepackrepository = construct(resourcePackRepositoryType, new Object[] {resourcePackSources});
        }

        /*
         * From MinecraftServer configurePackRepository - actually initialize the resource pack repository
         */
        {
            createFromCode(resourcepackrepository.getClass(),
                    "arg0.reload();\n" +
                    "arg0.setSelected(java.util.Collections.singleton(\"vanilla\"));\n" +
                    "return null;",
                    resourcepackrepository);
        }

        /*
         * Create a ResourceManager with a list of resource packs
         */
        Object resourcemanager;
        {
            java.util.List<?> packs = (java.util.List<?>) Resolver.resolveAndGetDeclaredMethod(resourcePackRepositoryType, "openAllSelected")
                    .invoke(resourcepackrepository);
            resourcemanager = construct(Class.forName("net.minecraft.server.packs.resources.ResourceManager"),
                    resourcepacktype, packs);
        }

        env.resourcePackRepository = resourcepackrepository;
        env.resourceManager = resourcemanager;
    }

    protected Object initRegistries(ServerEnvironment env, Class<?> minecraftServerType) throws Throwable {
        Object registries = createFromCode(minecraftServerType, "return net.minecraft.server.RegistryLayer.createRegistryAccess();");

        // Initialize WORLDGEN_REGISTRIES
        // In WorldLoader.java:
        //                 LayeredRegistryAccess<RegistryLayer> layeredregistryaccess1 = loadAndReplaceLayer(ireloadableresourcemanager, layeredregistryaccess, RegistryLayer.WORLDGEN, RegistryDataLoader.WORLDGEN_REGISTRIES);
        {
            registries = createFromCode(minecraftServerType,
                    "return net.minecraft.server.WorldLoader.loadAndReplaceLayer(\n" +
                    "    arg0, arg1,\n" +
                    "    net.minecraft.server.RegistryLayer.WORLDGEN,\n" +
                    "    net.minecraft.resources.RegistryDataLoader.WORLDGEN_REGISTRIES\n" +
                    ");",
                    env.resourceManager, registries);
        }

        return registries;
    }

    @SuppressWarnings("unchecked")
    protected void initDataPack(ServerEnvironment env, Class<?> minecraftServerType, Object mc_server, Object registries) throws Throwable {
        final Class<?> systemUtilsClass = loadClass("net.minecraft.SystemUtils", "net.minecraft.Util");

        /*
         * Initialize the DIMENSION_REGISTRIES (used for dimension type api)
         */
        {
            createFromCode(minecraftServerType,
                    "return net.minecraft.resources.RegistryDataLoader.load(arg0,\n" +
                    "            arg1.getAccessForLoading(net.minecraft.server.RegistryLayer.DIMENSIONS),\n" +
                    "            net.minecraft.resources.RegistryDataLoader.DIMENSION_REGISTRIES);",
                    env.resourceManager, registries);
        }

        /*
         * Retrieve reloadable ICustomRegistry.Dimension instance from the loaded resource pack
         */
        Object customRegistryDimension;
        {
            customRegistryDimension = createFromCode(Class.forName("net.minecraft.core.LayeredRegistryAccess"),
                                                     "return arg0.getAccessForLoading(net.minecraft.server.RegistryLayer.RELOADABLE);",
                                                     registries);
            /*
            customRegistryDimension = Resolver.resolveAndGetDeclaredMethod(
                    Class.forName("net.minecraft.core.LayeredRegistryAccess"), "compositeAccess")
                .invoke(registries);
            */
        }

        /*
         * Create a completable future completed when the resource pack is loaded fully.
         * Call get() on it to load it synchronously right here right now
         * 
         * CompletableFuture completablefuture = DataPackResources.a(
         *         resourcepackrepository.f(),
         *         CommandDispatcher.ServerType.DEDICATED,
         *         2, //dedicatedserversettings.getProperties().functionPermissionLevel,
         *         SystemUtils.f(),
         *         newThreadExecutor());
         */
        CompletableFuture<Object> futureDPLoaded;
        {
            Class<?> serverTypeType = loadClass(
                    "net.minecraft.commands.CommandDispatcher$ServerType",
                    "net.minecraft.commands.Commands$CommandSelection"
            );
            Object serverType = getStaticField(serverTypeType, "DEDICATED");
            Object featureFlagSet = createFromCode(Class.forName("net.minecraft.world.level.WorldDataConfiguration"),
                                                   "return WorldDataConfiguration.DEFAULT.enabledFeatures();");
            int functionPermissionLevel = 2;
            Executor executor1;
            if (CommonBootstrap.evaluateMCVersion(">=", "1.19.4")) {
                // They now have some hidden threadpool crap going on. So just don't bother.
                executor1 = newThreadExecutor();
            } else {
                executor1 = (Executor) Resolver.resolveAndGetDeclaredMethod(systemUtilsClass,
                        "bootstrapExecutor").invoke(null);
            }
            Executor executor2 = newThreadExecutor();
            Class<?> dataPackResourcesType = loadClass(
                    "net.minecraft.server.DataPackResources",
                    "net.minecraft.server.ReloadableServerResources"
            );
            Method startLoadingMethod = Resolver.resolveAndGetDeclaredMethod(dataPackResourcesType, "loadResources",
                    loadClass(
                            "net.minecraft.server.packs.resources.IResourceManager",
                            "net.minecraft.server.packs.resources.ResourceManager"
                    ),
                    loadClass(
                            "net.minecraft.core.IRegistryCustom$Dimension",
                            "net.minecraft.core.RegistryAccess$Frozen"
                    ),
                    Class.forName("net.minecraft.world.flag.FeatureFlagSet"),
                    serverTypeType,
                    int.class,
                    Executor.class,
                    Executor.class);
            futureDPLoaded = (CompletableFuture<Object>) startLoadingMethod.invoke(null,
                    env.resourceManager, customRegistryDimension, featureFlagSet, serverType, functionPermissionLevel, executor1, executor2);
        }

        // Retrieve it, using get(). May throw if problems occur.
        Object datapackresources = futureDPLoaded.get();

        // Call j() on the result - which calls bind() on the tags
        // datapackresources.i();
        {
            Class<?> datapackresourceType = loadClass(
                    "net.minecraft.server.DataPackResources",
                    "net.minecraft.server.ReloadableServerResources"
            );
            Resolver.resolveAndGetDeclaredMethod(datapackresourceType, "updateRegistryTags",
                    loadClass("net.minecraft.core.IRegistryCustom", "net.minecraft.core.RegistryAccess"))
                        .invoke(datapackresources, customRegistryDimension);
        }

        // Now set all these fields in the MinecraftServer instance
        setField(mc_server, "packRepository", env.resourcePackRepository);

        // As of 1.18.2 the 'resources' field is class that stores two fields inside (IReloadableResourceManager + DataPackResources)
        {
            String resourcesFieldName = Resolver.resolveFieldName(minecraftServerType, "resources");
            Field field = minecraftServerType.getDeclaredField(resourcesFieldName);
            field.setAccessible(true);

            Constructor<?> constr = field.getType().getConstructor(
                    loadClass(
                            "net.minecraft.server.packs.resources.IReloadableResourceManager",
                            "net.minecraft.server.packs.resources.CloseableResourceManager"
                    ),
                    loadClass(
                            "net.minecraft.server.DataPackResources",
                            "net.minecraft.server.ReloadableServerResources"
                    ));
            constr.setAccessible(true);
            Object resourceManagerForServer = env.resourceManager;
            if (CommonBootstrap.evaluateMCVersion(">=", "1.19.4")) {
                Class<?> packRepositoryType = Class.forName("net.minecraft.server.packs.repository.PackRepository");
                Object resourcepacktype = getStaticField(Class.forName("net.minecraft.server.packs.PackType"), "SERVER_DATA");
                java.util.List<?> packs = (java.util.List<?>) Resolver.resolveAndGetDeclaredMethod(packRepositoryType, "openAllSelected")
                        .invoke(env.resourcePackRepository);
                resourceManagerForServer = construct(
                        Class.forName("net.minecraft.server.packs.resources.MultiPackResourceManager"),
                        resourcepacktype, packs);
            }
            Object managerWithResources = constr.newInstance(resourceManagerForServer, datapackresources);

            field.set(mc_server, managerWithResources);
        }
    }
}
