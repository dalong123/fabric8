/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fusesource.fabric.itests.paxexam;

import java.io.File;
import java.util.Arrays;
import org.fusesource.fabric.api.Container;
import org.fusesource.fabric.api.CreateContainerMetadata;
import org.fusesource.fabric.api.CreateContainerOptions;
import org.fusesource.fabric.api.CreateContainerOptionsBuilder;
import org.fusesource.fabric.api.FabricService;
import org.fusesource.fabric.api.Profile;
import org.fusesource.fabric.api.Version;
import org.fusesource.fabric.internal.ZooKeeperUtils;
import org.fusesource.fabric.zookeeper.ZkPath;
import org.fusesource.tooling.testing.pax.exam.karaf.FuseTestSupport;
import org.linkedin.zookeeper.client.IZKClient;
import org.ops4j.pax.exam.MavenUtils;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.options.DefaultCompositeOption;


import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static org.openengsb.labs.paxexam.karaf.options.KarafDistributionOption.editConfigurationFilePut;
import static org.openengsb.labs.paxexam.karaf.options.KarafDistributionOption.karafDistributionConfiguration;
import static org.openengsb.labs.paxexam.karaf.options.KarafDistributionOption.useOwnExamBundlesStartLevel;
import static org.ops4j.pax.exam.CoreOptions.maven;

public class FabricTestSupport extends FuseTestSupport {

    static final String GROUP_ID = "org.fusesource.fabric";
    static final String ARTIFACT_ID = "fuse-fabric";

    static final String KARAF_GROUP_ID = "org.apache.karaf";
    static final String KARAF_ARTIFACT_ID = "apache-karaf";

    /**
     * Creates a child {@ling Agent} witht the given name.
     *
     *
     * @param name The name of the child {@ling Agent}.
     * @param parent
     * @return
     */
    protected Container createChildContainer(String name, String parent, String profileName) throws Exception {
        FabricService fabricService = getOsgiService(FabricService.class);
        assertNotNull(fabricService);

        Thread.sleep(DEFAULT_WAIT);

        Container parentContainer = fabricService.getContainer(parent);
        assertNotNull(parentContainer);


        CreateContainerOptions args = CreateContainerOptionsBuilder.child().name(name).parent(parent).jvmOpts("-Xms1024m -Xmx1024m");
        CreateContainerMetadata[] metadata = fabricService.createContainers(args);
        if (metadata.length > 0) {
            if (metadata[0].getFailure() != null) {
                throw new Exception("Error creating child container:"+name, metadata[0].getFailure());
            }
            Container container =  metadata[0].getContainer();
            Version version = fabricService.getDefaultVersion();
            Profile profile  = fabricService.getProfile(version.getName(),profileName);
            assertNotNull("Expected to find profile with name:" + profileName,profile);
            container.setProfiles(new Profile[]{profile});
            waitForProvisionSuccess(container, PROVISION_TIMEOUT);
            return container;
        }
        throw new Exception("Could container not created");
    }

    protected void destroyChildContainer(String name) throws InterruptedException {
        try {
            //Wait for zookeeper service to become available.
            IZKClient zooKeeper = getOsgiService(IZKClient.class);

            FabricService fabricService = getOsgiService(FabricService.class);
            assertNotNull(fabricService);

            Thread.sleep(DEFAULT_WAIT);
            Container container = fabricService.getContainer(name);
            container.destroy();
        } catch (Exception ex) {
            System.err.println(ex.getMessage());
        }
    }


    /**
     * Waits for a container to successfully provision.
     * @param container
     * @param timeout
     * @throws Exception
     */
    public void waitForProvisionSuccess(Container container, Long timeout) throws Exception {
        System.err.println("Waiting for container: " + container.getId() + " to succesfully provision");
        for (long t = 0; (!(container.isAlive() && container.getProvisionStatus().equals("success") && container.getSshUrl() != null) && t < timeout); t += 2000L) {
            if (container.getProvisionException() != null) {
                throw new Exception(container.getProvisionException());
            }
            Thread.sleep(2000L);
            System.err.println("Alive:"+container.isAlive()+" Status:"+ container.getProvisionStatus()+" SSH URL:" + container.getSshUrl());
        }
        if (!container.isAlive() || !container.getProvisionStatus().equals("success") ||  container.getSshUrl() == null) {
            throw new Exception("Could not provision " + container.getId() + " after " + timeout + " seconds. Alive:"+ container.isAlive()+" Status:"+container.getProvisionStatus()+" Ssh URL:"+container.getSshUrl());
        }
    }


    /**
     * Creates a child container, waits for succesfull provisioning and asserts, its asigned the right profile.
     * @param name
     * @param parent
     * @param profile
     * @throws Exception
     */
    public void createAndAssetChildContainer(String name, String parent, String profile) throws Exception {
        FabricService fabricService = getOsgiService(FabricService.class);
        assertNotNull(fabricService);

        Container child1 = createChildContainer(name, parent, profile);
        Container result = fabricService.getContainer(name);
        assertEquals("Containers should have the same id", child1.getId(), result.getId());
    }

    /**
     * Cleans a containers profile by switching to default profile and reseting the profile.
     * @param containerName
     * @param profileName
     * @throws Exception
     */
    public boolean containerSetProfile(String containerName, String profileName) throws Exception {
        System.out.println("Switching profile: "+profileName+" on container:"+containerName);
        FabricService fabricService = getOsgiService(FabricService.class);
        assertNotNull(fabricService);

        IZKClient zookeeper = getOsgiService(IZKClient.class);
        assertNotNull(zookeeper);

        Container container = fabricService.getContainer(containerName);
        Version version = container.getVersion();
        Profile[] profiles = new Profile[]{fabricService.getProfile(version.getName(),profileName)};
        Profile[] currentProfiles = container.getProfiles();

        Arrays.sort(profiles);
        Arrays.sort(currentProfiles);

        boolean same = true;
        if (profiles.length != currentProfiles.length) {
            same = false;
        } else {
            for (int i = 0; i < currentProfiles.length; i++) {
                if (!currentProfiles[i].configurationEquals(profiles[i])) {
                    same = false;
                }
            }
        }

        if (!same) {
            //This is required so that waitForProvisionSuccess doesn't retrun before the deployment agent kicks in.
            ZooKeeperUtils.set(zookeeper, ZkPath.CONTAINER_PROVISION_RESULT.getPath(containerName), "switching profile");
            container.setProfiles(profiles);
            waitForProvisionSuccess(container, PROVISION_TIMEOUT);
        }
        return same;
    }



    /**
     * Returns the Version of Karaf to be used.
     *
     * @return
     */
    protected String getKarafVersion() {
        //TODO: This is a hack because pax-exam-karaf will not work with non numeric characters in the version.
        //We will need to change it once pax-exam-karaf get fixed (version 0.4.0 +).
        return "2.2.5";
    }

    /**
     * Create an {@link Option} for using a Fabric distribution.
     *
     * @return
     */
    protected Option fabricDistributionConfiguration() {
        return new DefaultCompositeOption(
                new Option[]{karafDistributionConfiguration().frameworkUrl(
                        maven().groupId(GROUP_ID).artifactId(ARTIFACT_ID).versionAsInProject().type("tar.gz"))
                        .karafVersion(getKarafVersion()).name("Fabric Karaf Distro").unpackDirectory(new File("target/paxexam/unpack/")),
                        useOwnExamBundlesStartLevel(40),
                      editConfigurationFilePut("etc/config.properties", "karaf.startlevel.bundle", "35"),
                      mavenBundle("org.fusesource.tooling.testing","pax-exam-karaf", MavenUtils.getArtifactVersion("org.fusesource.tooling.testing","pax-exam-karaf"))
                });
    }
}
