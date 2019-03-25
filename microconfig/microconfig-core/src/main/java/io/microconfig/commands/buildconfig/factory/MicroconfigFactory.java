package io.microconfig.commands.buildconfig.factory;

import io.microconfig.commands.buildconfig.BuildConfigCommand;
import io.microconfig.commands.buildconfig.BuildConfigPostProcessor;
import io.microconfig.configs.ConfigProvider;
import io.microconfig.configs.io.ioservice.ConfigIoService;
import io.microconfig.configs.io.ioservice.properties.PropertiesConfigIoService;
import io.microconfig.configs.io.ioservice.selector.ConfigFormatDetectorImpl;
import io.microconfig.configs.io.ioservice.selector.ConfigIoServiceSelector;
import io.microconfig.configs.io.ioservice.yaml.YamlConfigIoService;
import io.microconfig.configs.io.tree.ComponentTree;
import io.microconfig.configs.io.tree.ComponentTreeCache;
import io.microconfig.configs.provider.ComponentParserImpl;
import io.microconfig.configs.provider.FileBasedConfigProvider;
import io.microconfig.configs.resolver.PropertyResolver;
import io.microconfig.configs.resolver.ResolvedConfigProvider;
import io.microconfig.configs.resolver.expression.ExpressionResolver;
import io.microconfig.configs.resolver.placeholder.PlaceholderResolveStrategy;
import io.microconfig.configs.resolver.placeholder.PlaceholderResolver;
import io.microconfig.configs.resolver.placeholder.strategies.component.ComponentResolveStrategy;
import io.microconfig.configs.resolver.placeholder.strategies.component.properties.ComponentPropertiesFactory;
import io.microconfig.configs.resolver.placeholder.strategies.envdescriptor.EnvDescriptorResolveStrategy;
import io.microconfig.configs.resolver.placeholder.strategies.envdescriptor.properties.EnvDescriptorPropertiesFactory;
import io.microconfig.configs.resolver.placeholder.strategies.standard.StandardResolveStrategy;
import io.microconfig.configs.serializer.ConfigSerializer;
import io.microconfig.configs.serializer.DiffSerializer;
import io.microconfig.configs.serializer.FilenameGeneratorImpl;
import io.microconfig.configs.serializer.ToFileConfigSerializer;
import io.microconfig.environments.EnvironmentProvider;
import io.microconfig.environments.filebased.EnvironmentParserSelectorImpl;
import io.microconfig.environments.filebased.FileBasedEnvironmentProvider;
import io.microconfig.utils.reader.FilesReader;
import io.microconfig.utils.reader.FsFilesReader;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Wither;

import java.io.File;

import static io.microconfig.commands.buildconfig.BuildConfigPostProcessor.emptyPostProcessor;
import static io.microconfig.configs.resolver.placeholder.PlaceholderResolveStrategy.composite;
import static io.microconfig.configs.resolver.placeholder.strategies.system.SystemResolveStrategy.envVariablesResolveStrategy;
import static io.microconfig.configs.resolver.placeholder.strategies.system.SystemResolveStrategy.systemPropertiesResolveStrategy;
import static io.microconfig.environments.filebased.EnvironmentParserImpl.jsonParser;
import static io.microconfig.environments.filebased.EnvironmentParserImpl.yamlParser;
import static io.microconfig.utils.CacheHandler.cache;
import static io.microconfig.utils.CollectionUtils.joinToSet;
import static io.microconfig.utils.FileUtils.canonical;
import static lombok.AccessLevel.PRIVATE;

@Getter
@RequiredArgsConstructor(access = PRIVATE)
public class MicroconfigFactory {
    public static final String ENV_DIR = "envs";

    private final ComponentTree componentTree;
    private final EnvironmentProvider environmentProvider;
    private final ConfigIoService configIoService;
    private final File destinationComponentDir;
    @Wither
    private final String serviceInnerDir;

    public static MicroconfigFactory init(File sourcesRootDir, File destinationComponentDir) {
        return init(sourcesRootDir, destinationComponentDir, new FsFilesReader());
    }

    public static MicroconfigFactory init(File sourcesRootDir, File destinationComponentDir, FilesReader fileReader) {
        File fullSourcesRootDir = canonical(sourcesRootDir);

        return new MicroconfigFactory(
                ComponentTreeCache.prepare(fullSourcesRootDir),
                newEnvProvider(fullSourcesRootDir, fileReader),
                newConfigIoService(fileReader),
                destinationComponentDir,
                ""
        );
    }

    public ConfigProvider newConfigProvider(ConfigType configType) {
        ConfigProvider fileBasedProvider = newFileBasedProvider(configType);
        return cache(
                new ResolvedConfigProvider(fileBasedProvider, newExpressionResolver(fileBasedProvider))
        );
    }

    public ConfigProvider newFileBasedProvider(ConfigType configType) {
        return cache(
                new FileBasedConfigProvider(configType.getConfigExtensions(), componentTree, new ComponentParserImpl(configIoService))
        );
    }

    public PropertyResolver newExpressionResolver(ConfigProvider simpleProvider) {
        return cache(new ExpressionResolver(cache(newPlaceholderResolver(simpleProvider))));
    }

    public PlaceholderResolver newPlaceholderResolver(ConfigProvider simpleProvider) {
        ComponentPropertiesFactory componentProperties = new ComponentPropertiesFactory(componentTree, destinationComponentDir);
        EnvDescriptorPropertiesFactory envProperties = new EnvDescriptorPropertiesFactory();

        PlaceholderResolveStrategy strategy = composite(
                systemPropertiesResolveStrategy(),
                new ComponentResolveStrategy(componentProperties.get()),
                new EnvDescriptorResolveStrategy(environmentProvider, envProperties.get()),
                new StandardResolveStrategy(simpleProvider),
                envVariablesResolveStrategy()
        );

        return new PlaceholderResolver(
                environmentProvider,
                strategy,
                joinToSet(componentProperties.get().keySet(), envProperties.get().keySet())
        );
    }

    public BuildConfigCommand newBuildCommand(ConfigType type) {
        return newBuildCommand(type, emptyPostProcessor());
    }

    public BuildConfigCommand newBuildCommand(ConfigType type, BuildConfigPostProcessor buildConfigPostProcessor) {
        return new BuildConfigCommand(
                environmentProvider,
                newConfigProvider(type),
                configSerializer(type),
                buildConfigPostProcessor
        );
    }

    private ConfigSerializer configSerializer(ConfigType configType) {
        return new DiffSerializer(
                new ToFileConfigSerializer(
                        new FilenameGeneratorImpl(destinationComponentDir, serviceInnerDir, configType.getResultFileName()),
                        configIoService
                ),
                configIoService
        );
    }

    private static EnvironmentProvider newEnvProvider(File root, FilesReader fileReader) {
        return cache(
                new FileBasedEnvironmentProvider(
                        new File(root, ENV_DIR),
                        new EnvironmentParserSelectorImpl(jsonParser(), yamlParser()),
                        fileReader
                )
        );
    }

    private static ConfigIoService newConfigIoService(FilesReader fileReader) {
        return new ConfigIoServiceSelector(
                cache(new ConfigFormatDetectorImpl(fileReader)),
                new YamlConfigIoService(fileReader),
                new PropertiesConfigIoService(fileReader)
        );
    }
}