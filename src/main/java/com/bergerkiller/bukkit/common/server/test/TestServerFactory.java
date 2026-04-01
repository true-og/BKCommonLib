package com.bergerkiller.bukkit.common.server.test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.io.Reader;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;

import org.bukkit.configuration.file.YamlConfiguration;

import com.bergerkiller.bukkit.common.Logging;
import com.bergerkiller.bukkit.common.internal.CommonBootstrap;
import com.bergerkiller.bukkit.common.server.CommonServerBase;
import com.bergerkiller.bukkit.common.utils.CommonUtil;
import com.bergerkiller.bukkit.common.utils.LogicUtil;
import com.bergerkiller.mountiplex.reflection.ClassHook;
import com.bergerkiller.mountiplex.reflection.declarations.ClassResolver;
import com.bergerkiller.mountiplex.reflection.declarations.MethodDeclaration;
import com.bergerkiller.mountiplex.reflection.resolver.Resolver;
import com.bergerkiller.mountiplex.reflection.util.FastMethod;

/**
 * This facility creates an appropriate Server object for use within Bukkit.
 * It should accurately try to detect and create the server BKCommonLib is linked against.
 */
public abstract class TestServerFactory {

    public static void initTestServer() {
        if (CommonServerBase.SERVER_CLASS == null) {
            throw new UnsupportedOperationException("Unable to detect Bukkit server main class during test");
        }

        TestServerFactory factory;
        if (CommonBootstrap.evaluateMCVersion(">=", "1.19.3")) {
            factory = new TestServerFactory_1_19_3();
        } else if (CommonBootstrap.evaluateMCVersion(">=", "1.18.2")) {
            factory = new TestServerFactory_1_18_2();
        } else if (CommonBootstrap.evaluateMCVersion(">=", "1.18")) {
            factory = new TestServerFactory_1_18();
        } else if (CommonBootstrap.evaluateMCVersion(">=", "1.17")) {
            factory = new TestServerFactory_1_17();
        } else if (CommonBootstrap.evaluateMCVersion(">=", "1.16")) {
            factory = new TestServerFactory_1_16();
        } else if (CommonBootstrap.evaluateMCVersion(">=", "1.14")) {
            factory = new TestServerFactory_1_14();
        } else {
            factory = new TestServerFactory_1_8();
        }

        ServerEnvironment env = new ServerEnvironment();
        try {
            env.CB_ROOT = factory.detectCBRoot();
            env.NMS_ROOT = factory.detectNMSRoot();
            factory.init(env);
        } catch (Throwable t) {
            Logging.LOGGER.severe("Failed to initialize server under test");
            Logging.LOGGER.severe("Detected server class under test: " + CommonServerBase.SERVER_CLASS);
            Logging.LOGGER.severe("Detected CB_ROOT: " + env.CB_ROOT);
            Logging.LOGGER.severe("Detected NMS_ROOT: " + env.NMS_ROOT);
            throw new UnsupportedOperationException("An error occurred while trying to initialize the test server", t);
        }

        init_spigotConfig();
        System.gc();
    }

    protected String detectCBRoot() throws Throwable {
        return getPackagePath(CommonServerBase.SERVER_CLASS);
    }

    protected String detectNMSRoot() throws Throwable {
        return "net.minecraft.server";
    }

    /**
     * Initializes the test server
     *
     * @param env Server environment details detected so far
     */
    protected abstract void init(ServerEnvironment env) throws Throwable;

    private static void init_spigotConfig() {
        Class<?> spigotConfigType = CommonUtil.getClass("org.spigotmc.SpigotConfig");
        if (spigotConfigType != null) {
            String yamlType = com.bergerkiller.bukkit.common.server.test.TestServerFactory.DefaultSpigotYamlConfiguration.class.getName();
            createFromCode(spigotConfigType,
                    "SpigotConfig.config = new " + yamlType + "();\n" +
                    "SpigotConfig.config.set(\"world-settings.default.verbose\", Boolean.FALSE);\n" +
                    "return null;\n");
        }

        Class<?> purpurConfigType = CommonUtil.getClass("org.purpurmc.purpur.PurpurConfig");
        if (purpurConfigType != null) {
            try {
                org.bukkit.configuration.file.YamlConfiguration config =
                        new DefaultSpigotYamlConfiguration();
                config.set("settings.verbose", Boolean.FALSE);

                Field configField = purpurConfigType.getDeclaredField("config");
                configField.setAccessible(true);
                configField.set(null, config);
            } catch (Throwable t) {
                throw new RuntimeException("Failed to initialize PurpurConfig", t);
            }
        }

        Class<?> globalConfigurationType = CommonUtil.getClass("io.papermc.paper.configuration.GlobalConfiguration");
        if (globalConfigurationType != null) {
            try {
                java.lang.reflect.Method getMethod = globalConfigurationType.getDeclaredMethod("get");
                Object currentConfig = getMethod.invoke(null);
                if (currentConfig == null) {
                    Object config = instantiateConfigurationPart(globalConfigurationType, null);
                    try {
                        java.lang.reflect.Method setMethod = globalConfigurationType.getDeclaredMethod("set", globalConfigurationType);
                        setMethod.setAccessible(true);
                        setMethod.invoke(null, config);
                    } catch (NoSuchMethodException ex) {
                        Field instanceField = globalConfigurationType.getDeclaredField("instance");
                        instanceField.setAccessible(true);
                        instanceField.set(null, config);
                    }
                }
            } catch (Throwable t) {
                throw new RuntimeException("Failed to initialize Paper GlobalConfiguration", t);
            }
        }
    }

    private static Object instantiateConfigurationPart(Class<?> type, Object outerInstance) throws Throwable {
        java.lang.reflect.Constructor<?> constructor;
        Object instance;
        if (type.getEnclosingClass() != null && !Modifier.isStatic(type.getModifiers())) {
            constructor = type.getDeclaredConstructor(type.getEnclosingClass());
            constructor.setAccessible(true);
            instance = constructor.newInstance(outerInstance);
        } else {
            constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            instance = constructor.newInstance();
        }

        for (Field field : type.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                continue;
            }
            if (!field.getType().getName().startsWith("io.papermc.paper.configuration.")) {
                continue;
            }

            field.setAccessible(true);
            if (field.get(instance) == null) {
                field.set(instance, instantiateConfigurationPart(field.getType(), instance));
            }
        }

        return instance;
    }

    protected static void setField(Object instance, Class<?> declaringClass, String name, Object value) {
        String realName = Resolver.resolveFieldName(declaringClass, name);
        try {
            Field field = declaringClass.getDeclaredField(realName);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (Throwable ex) {
            throw new RuntimeException("Failed to set field " + fieldAliasStr(name, realName), ex);
        }
    }

    protected static void setField(Object instance, String name, Object value) {
        Field f = null;
        Class<?> t = instance.getClass();
        while (t != null && f == null) {
            try {
                String realName = Resolver.resolveFieldName(t, name);
                f = t.getDeclaredField(realName);
                f.setAccessible(true);
            } catch (Throwable ex) {}
            t = t.getSuperclass();
        }
        if (f == null) {
            String realName = Resolver.resolveFieldName(instance.getClass(), name);
            throw new RuntimeException("Field " + fieldAliasStr(name, realName) + " not found in " + instance.getClass().getName());
        }
        try {
            f.set(instance, value);
        } catch (Throwable ex) {
            throw new RuntimeException("Failed to set field " + name, ex);
        }
    }

    protected static Object getStaticField(Class<?> type, String name) {
        String realName = Resolver.resolveFieldName(type, name);
        try {
            java.lang.reflect.Field f = type.getDeclaredField(realName);
            f.setAccessible(true);;
            return f.get(null);
        } catch (Throwable ex) {
            throw new RuntimeException("Failed to get field " + fieldAliasStr(name, realName), ex);
        }
    }

    private static String fieldAliasStr(String name, String realName) {
        return name.equals(realName) ? name : (name + ":" + realName);
    }
    
    protected static Object createFromCode(Class<?> type, String code) {
        return compileCode(type, "public static Object create() {" + code + "}").invoke(null);
    }

    protected static Object createFromCode(Class<?> type, String code, Object... args) {
        StringBuilder body = new StringBuilder();
        body.append("public static Object create(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                body.append(", ");
            }
            Class<?> argType = (args[i] == null) ? Object.class : args[i].getClass();
            body.append(argType.getName()).append(" arg").append(i);
        }
        body.append(") {").append(code).append("}");
        return compileCode(type, body.toString()).invokeVA(null, args);
    }

    protected static FastMethod<Object> compileCode(Class<?> type, String code) {
        ClassResolver resolver = new ClassResolver();
        resolver.setDeclaredClass(type);
        MethodDeclaration dec = new MethodDeclaration(resolver, code);
        FastMethod<Object> m = new FastMethod<Object>();
        m.init(dec);
        return m;
    }

    protected static Executor newThreadExecutor() {
        return runnable -> {
            Thread t = new Thread(runnable, "TestServerWorkerThread");
            t.setDaemon(true);
            t.start();
        };
    }

    protected static Object construct(Class<?> type, Object... parameters) {
        try {
            for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
                Class<?>[] paramTypes = constructor.getParameterTypes();
                if (paramTypes.length == parameters.length) {
                    boolean suitable = true;
                    for (int i = 0; i < paramTypes.length; i++) {
                        if (parameters[i] == null) {
                            // Primitive types can not be assigned null
                            if (parameters[i].getClass().isPrimitive()) {
                                suitable = false;
                                break;
                            }
                            continue;
                        }

                        Class<?> paramTypeFixed = LogicUtil.tryBoxType(paramTypes[i]);
                        if (!paramTypeFixed.isAssignableFrom(parameters[i].getClass())) {
                            suitable = false;
                            break;
                        }
                    }
                    if (suitable) {
                        constructor.setAccessible(true);
                        return constructor.newInstance(parameters);
                    }
                }
            }
        } catch (Throwable t) {
            throw new RuntimeException("Failed to construct " + type.getSimpleName(), t);
        }
        throw new RuntimeException("Constructor not found in " + type.getSimpleName());
    }

    protected static String getPackagePath(Class<?> type) {
        return type.getPackage().getName();
    }

    /**
     * Purpur 1.19.4 expects a JavaScript engine on Entity.scriptEngine, but modern
     * JDKs no longer ship one by default. Tests only need basic numeric expressions.
     */
    protected static final class DummyScriptEngine extends AbstractScriptEngine {
        private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");

        @Override
        public Object eval(String script, ScriptContext context) throws ScriptException {
            if (script == null) {
                return 0.0;
            }

            Matcher matcher = NUMBER_PATTERN.matcher(script);
            if (matcher.find()) {
                String number = matcher.group();
                return number.contains(".") ? Double.parseDouble(number) : Integer.parseInt(number);
            }

            return 0.0;
        }

        @Override
        public Object eval(Reader reader, ScriptContext context) throws ScriptException {
            return 0.0;
        }

        @Override
        public Bindings createBindings() {
            return new SimpleBindings();
        }

        @Override
        public ScriptEngineFactory getFactory() {
            return null;
        }
    }

    public static class ServerHook extends ClassHook<ServerHook> {
        @HookMethod("public String getVersion()")
        public String getVersion() {
            return "DUMMY_SERVER";
        }

        @HookMethod("public String getBukkitVersion()")
        public String getBukkitVersion() {
            return "DUMMY_BUKKIT";
        }
    }

    public static class DefaultSpigotYamlConfiguration extends YamlConfiguration {

        public DefaultSpigotYamlConfiguration() {
            this.set("world-settings.default.verbose", false);
        }

        @Override
        public void save(File file) {
            // Denied.
        }

        @Override
        public void save(String file) {
            // Denied.
        }
    }

    protected static final class ServerEnvironment {
        public String CB_ROOT = "FAIL";
        public String NMS_ROOT = "FAIL";
        public Object resourcePackRepository = null;
        public Object resourceManager = null;
    }
}
