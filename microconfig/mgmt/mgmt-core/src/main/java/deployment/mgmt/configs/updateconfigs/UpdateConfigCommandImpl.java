package deployment.mgmt.configs.updateconfigs;

import io.microconfig.configs.command.BuildConfigMain;
import deployment.mgmt.configs.componentgroup.ComponentGroupService;
import deployment.mgmt.configs.componentgroup.GroupDescription;
import deployment.mgmt.configs.filestructure.DeployFileStructure;
import deployment.mgmt.process.stop.StopCommand;
import deployment.mgmt.update.scriptgenerator.MgmtScriptGenerator;
import lombok.RequiredArgsConstructor;

import java.io.File;
import java.util.List;

import static deployment.mgmt.configs.updateconfigs.UpdateConfigOption.*;
import static deployment.util.FileUtils.delete;
import static deployment.util.Logger.announce;
import static deployment.util.Logger.info;
import static deployment.util.PropertiesUtils.hasSystemFlag;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

@RequiredArgsConstructor
public class UpdateConfigCommandImpl implements UpdateConfigCommand {
    private final ComponentGroupService componentGroupService;
    private final DeployFileStructure deployFileStructure;
    private final StopCommand stopCommand;
    private final NewServicePreparer newServicePreparer;
    private final MgmtScriptGenerator mgmtScriptGenerator;

    @Override
    public void buildConfig(UpdateConfigOption... options) {
        announce("Going to build configs");
        List<String> servicesBeforeUpdate = componentGroupService.getServices();

        buildConfigs();
        applyAlteredVersions(options);
        stopRemovedServices(servicesBeforeUpdate);
        generateMgmtScript();
        prepareNewServices(options);

        announce("Updated configs");
    }

    private void stopRemovedServices(List<String> servicesBeforeUpdate) {
        List<String> currentServices = componentGroupService.getServices();

        servicesBeforeUpdate.parallelStream()
                .filter(s -> !currentServices.contains(s))
                .forEach(s -> {
                    stopCommand.stop(s);
                    delete(deployFileStructure.service().getServiceDir(s));
                });
    }

    private void buildConfigs() {
        GroupDescription groupDescription = componentGroupService.getDescription();

        File repoDir = deployFileStructure.configs().getInnerRepoDir();
        File componentsDir = deployFileStructure.service().getComponentsDir();
        info("Source: " + repoDir + ". Destination: " + componentsDir);

        BuildConfigMain.execute(
                groupDescription.getEnv(),
                singletonList(groupDescription.getGroup()),
                repoDir,
                componentsDir,
                emptyList()
        );
    }

    private void applyAlteredVersions(UpdateConfigOption... options) {
        if (in(options, CLEAN_ALTERED_VERSIONS)) {
            componentGroupService.cleanAlteredVersions();
        } else {
            componentGroupService.replaceServiceVersionWithAltered();
        }
    }

    private void generateMgmtScript() {
        mgmtScriptGenerator.generateMgmtScript();
    }

    private void prepareNewServices(UpdateConfigOption... options) {
        if (hasSystemFlag("simple")) return;

        newServicePreparer.prepare(componentGroupService.getServices(), in(options, SKIP_CLASSPATH_BUILD_FOR_SNAPSHOT));
    }
}