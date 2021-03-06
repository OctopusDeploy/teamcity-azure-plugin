/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.clouds.azure.arm.connector;

import com.intellij.openapi.diagnostic.Logger;
import com.microsoft.azure.ListOperationCallback;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.management.compute.ComputeManagementClient;
import com.microsoft.azure.management.compute.ComputeManagementClientImpl;
import com.microsoft.azure.management.compute.models.*;
import com.microsoft.azure.management.network.NetworkManagementClient;
import com.microsoft.azure.management.network.NetworkManagementClientImpl;
import com.microsoft.azure.management.network.models.*;
import com.microsoft.azure.management.resources.ResourceManagementClient;
import com.microsoft.azure.management.resources.ResourceManagementClientImpl;
import com.microsoft.azure.management.resources.SubscriptionClient;
import com.microsoft.azure.management.resources.SubscriptionClientImpl;
import com.microsoft.azure.management.resources.models.*;
import com.microsoft.azure.management.storage.StorageManagementClient;
import com.microsoft.azure.management.storage.StorageManagementClientImpl;
import com.microsoft.azure.management.storage.models.StorageAccount;
import com.microsoft.azure.management.storage.models.StorageAccountKeys;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageCredentialsAccountAndKey;
import com.microsoft.azure.storage.blob.*;
import com.microsoft.rest.ServiceCallback;
import com.microsoft.rest.ServiceResponse;
import com.microsoft.rest.credentials.ServiceClientCredentials;
import jetbrains.buildServer.clouds.CloudException;
import jetbrains.buildServer.clouds.CloudInstanceUserData;
import jetbrains.buildServer.clouds.InstanceStatus;
import jetbrains.buildServer.clouds.azure.arm.*;
import jetbrains.buildServer.clouds.azure.arm.connector.models.*;
import jetbrains.buildServer.clouds.azure.arm.utils.AzureUtils;
import jetbrains.buildServer.clouds.azure.connector.AzureApiConnectorBase;
import jetbrains.buildServer.clouds.azure.utils.AlphaNumericStringComparator;
import jetbrains.buildServer.clouds.base.connector.AbstractInstance;
import jetbrains.buildServer.clouds.base.errors.CheckedCloudException;
import jetbrains.buildServer.clouds.base.errors.TypedCloudErrorInfo;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.util.CollectionsUtil;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.util.filters.Filter;
import okhttp3.OkHttpClient;
import org.apache.commons.codec.binary.Base64;
import org.jdeferred.*;
import org.jdeferred.impl.DefaultDeferredManager;
import org.jdeferred.impl.DeferredObject;
import org.jdeferred.multiple.MultipleResults;
import org.jdeferred.multiple.OneReject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joda.time.DateTime;
import retrofit2.Retrofit;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides azure arm management capabilities.
 */
public class AzureApiConnectorImpl extends AzureApiConnectorBase<AzureCloudImage, AzureCloudInstance> implements AzureApiConnector {

    private static final Logger LOG = Logger.getInstance(AzureApiConnectorImpl.class.getName());
    private static final String FAILED_TO_GET_INSTANCE_STATUS_FORMAT = "Failed to get instance %s status: %s";
    private static final Pattern RESOURCE_GROUP_PATTERN = Pattern.compile("resourceGroups/(.+)/providers/");
    private static final int RESOURCES_NUMBER = 100;
    private static final String PUBLIC_IP_SUFFIX = "-pip";
    private static final String PROVISIONING_STATE = "ProvisioningState/";
    private static final String POWER_STATE = "PowerState/";
    private static final String INSTANCE_VIEW = "InstanceView";
    private static final String NOT_FOUND_ERROR = "Invalid status code 404";
    private static final String AZURE_URL = "https://management.azure.com";
    private static final String HTTP_PROXY_HOST = "http.proxyHost";
    private static final String HTTP_PROXY_PORT = "http.proxyPort";
    private static final String HTTPS_PROXY_HOST = "https.proxyHost";
    private static final String HTTPS_PROXY_PORT = "https.proxyPort";
    private static final String HTTP_PROXY_USER = "http.proxyUser";
    private static final String HTTP_PROXY_PASSWORD = "http.proxyPassword";
    private static final List<InstanceStatus> PROVISIONING_STATES = Arrays.asList(
            InstanceStatus.SCHEDULED_TO_START,
            InstanceStatus.SCHEDULED_TO_STOP);
    private final ResourceManagementClient myArmClient;
    private final StorageManagementClient myStorageClient;
    private final ComputeManagementClient myComputeClient;
    private final NetworkManagementClient myNetworkClient;
    private final SubscriptionClient mySubscriptionClient;
    private final DefaultDeferredManager myManager;
    private String myServerId = null;
    private String myProfileId = null;
    private String myLocation = null;

    public AzureApiConnectorImpl(@NotNull final String tenantId,
                                 @NotNull final String clientId,
                                 @NotNull final String secret) {
        final ServiceClientCredentials credentials = new ApplicationTokenCredentials(clientId, tenantId, secret, null);
        final OkHttpClient.Builder httpClientBuilder = new OkHttpClient.Builder();
        final Retrofit.Builder retrofitBuilder = new Retrofit.Builder();
        configureProxy(httpClientBuilder);
        myArmClient = new ResourceManagementClientImpl(AZURE_URL, credentials, httpClientBuilder, retrofitBuilder);
        myStorageClient = new StorageManagementClientImpl(AZURE_URL, credentials, httpClientBuilder, retrofitBuilder);
        myComputeClient = new ComputeManagementClientImpl(AZURE_URL, credentials, httpClientBuilder, retrofitBuilder);
        myNetworkClient = new NetworkManagementClientImpl(AZURE_URL, credentials, httpClientBuilder, retrofitBuilder);
        mySubscriptionClient = new SubscriptionClientImpl(AZURE_URL, credentials, httpClientBuilder, retrofitBuilder);
        myManager = new DefaultDeferredManager();
    }

    /**
     * Configures http proxy settings.
     *
     * @param builder is a http builder.
     */
    private static void configureProxy(@NotNull final OkHttpClient.Builder builder) {
        // Set HTTP proxy
        final String httpProxyHost = TeamCityProperties.getProperty(HTTP_PROXY_HOST);
        final int httpProxyPort = TeamCityProperties.getInteger(HTTP_PROXY_PORT, 80);
        if (!StringUtil.isEmpty(httpProxyHost)) {
            builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(httpProxyHost, httpProxyPort)));
        }

        // Set HTTPS proxy
        final String httpsProxyHost = TeamCityProperties.getProperty(HTTPS_PROXY_HOST);
        final int httpsProxyPort = TeamCityProperties.getInteger(HTTPS_PROXY_PORT, 443);
        if (!StringUtil.isEmpty(httpsProxyHost)) {
            builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(httpsProxyHost, httpsProxyPort)));
        }

        // Set proxy authentication
        final String httpProxyUser = TeamCityProperties.getProperty(HTTP_PROXY_USER);
        final String httpProxyPassword = TeamCityProperties.getProperty(HTTP_PROXY_PASSWORD);
        if (!StringUtil.isEmpty(httpProxyUser)) {
            builder.proxyAuthenticator(new CredentialsAuthenticator(httpProxyUser, httpProxyPassword));
        }
    }

    @Override
    public void test() throws CloudException {
        try {
            myArmClient.getResourceGroupsOperations().list(null, RESOURCES_NUMBER);
        } catch (Exception e) {
            final String message = "Failed to get list of groups: " + e.getMessage();
            LOG.debug(message, e);
            throw new CloudException(message, e);
        }
    }

    @Nullable
    @Override
    public InstanceStatus getInstanceStatusIfExists(@NotNull final AzureCloudInstance instance) {
        final AzureInstance azureInstance = new AzureInstance(instance.getName());
        final AzureCloudImageDetails details = instance.getImage().getImageDetails();
        final InstanceStatus[] instanceStatus = {null};

        try {
            myManager.when(getInstanceDataAsync(azureInstance, details)).fail(new FailCallback<Throwable>() {
                @Override
                public void onFail(Throwable result) {
                    final Throwable cause = result.getCause();
                    final String message = String.format(FAILED_TO_GET_INSTANCE_STATUS_FORMAT, instance.getName(), result.getMessage());
                    LOG.debug(message, result);
                    if (cause != null && NOT_FOUND_ERROR.equals(cause.getMessage()) ||
                            PROVISIONING_STATES.contains(instance.getStatus())) {
                        return;
                    }
                    instance.setStatus(InstanceStatus.ERROR);
                    instance.updateErrors(TypedCloudErrorInfo.fromException(result));
                }
            }).done(new DoneCallback<Void>() {
                @Override
                public void onDone(Void result) {
                    final InstanceStatus status = azureInstance.getInstanceStatus();
                    LOG.debug(String.format("Instance %s status is %s", instance.getName(), status));
                    instance.setStatus(status);
                    instance.updateErrors();
                    instanceStatus[0] = status;
                }
            }).waitSafely();
        } catch (InterruptedException e) {
            final String message = String.format(FAILED_TO_GET_INSTANCE_STATUS_FORMAT, instance.getName(), e);
            LOG.debug(message, e);
            final CloudException exception = new CloudException(message, e);
            instance.updateErrors(TypedCloudErrorInfo.fromException(exception));
        }

        return instanceStatus[0];
    }

    @NotNull
    @Override
    @SuppressWarnings("unchecked")
    public <R extends AbstractInstance> Map<AzureCloudImage, Map<String, R>> fetchInstances(@NotNull Collection<AzureCloudImage> images) throws CheckedCloudException {
        final Map<AzureCloudImage, Map<String, R>> imageMap = new HashMap<>();
        final List<Promise<Void, Throwable, Void>> promises = new ArrayList<>();

        for (final AzureCloudImage image : images) {
            final Promise<Void, Throwable, Void> promise = fetchInstancesAsync(image).fail(new FailCallback<Throwable>() {
                @Override
                public void onFail(Throwable result) {
                    LOG.warn(String.format("Failed to receive list of image %s instances: %s", image.getName(), result.getMessage()), result);
                    image.updateErrors(TypedCloudErrorInfo.fromException(result));
                }
            }).then(new DonePipe<Map<String, AbstractInstance>, Void, Throwable, Void>() {
                @Override
                public Promise<Void, Throwable, Void> pipeDone(Map<String, AbstractInstance> result) {
                    LOG.debug(String.format("Received list of image %s instances", image.getName()));
                    image.updateErrors();
                    imageMap.put(image, (Map<String, R>) result);
                    return new DeferredObject<Void, Throwable, Void>().resolve(null);
                }
            });

            promises.add(promise);
        }

        if (promises.size() != 0) {
            try {
                myManager.when(promises.toArray(new Promise[]{})).waitSafely();
            } catch (InterruptedException e) {
                final String message = "Failed to get list of images: " + e.getMessage();
                LOG.debug(message, e);
                throw new CloudException(message, e);
            }
        }

        return imageMap;
    }

    @SuppressWarnings("unchecked")
    private <R extends AbstractInstance> Promise<Map<String, R>, Throwable, Void> fetchInstancesAsync(final AzureCloudImage image) {
        final DeferredObject<Map<String, R>, Throwable, Void> deferred = new DeferredObject<>();
        final List<Promise<Void, Throwable, Void>> promises = new ArrayList<>();
        final Map<String, R> instances = new HashMap<>();
        final List<Throwable> exceptions = new ArrayList<>();
        final AzureCloudImageDetails details = image.getImageDetails();

        getVirtualMachinesAsync().fail(new FailCallback<Throwable>() {
            @Override
            public void onFail(Throwable t) {
                final String message = String.format("Failed to get list of instances for cloud image %s: %s", image.getName(), t.getMessage());
                LOG.debug(message, t);
                final CloudException exception = new CloudException(message, t);
                exceptions.add(exception);
                deferred.reject(exception);
            }
        }).then(new DoneCallback<List<VirtualMachine>>() {
            @Override
            public void onDone(List<VirtualMachine> machines) {
                for (VirtualMachine virtualMachine : machines) {
                    final String name = virtualMachine.getName();
                    if (!name.startsWith(details.getVmNamePrefix())) {
                        LOG.debug("Ignore vm with name " + name);
                        continue;
                    }

                    final Map<String, String> tags = virtualMachine.getTags();
                    if (tags == null) {
                        LOG.debug("Ignore vm without tags");
                        continue;
                    }

                    final String serverId = tags.get(AzureConstants.TAG_SERVER);
                    if (!StringUtil.areEqual(serverId, myServerId)) {
                        LOG.debug("Ignore vm with invalid server tag " + serverId);
                        continue;
                    }

                    final String profileId = tags.get(AzureConstants.TAG_PROFILE);
                    if (!StringUtil.areEqual(profileId, myProfileId)) {
                        LOG.debug("Ignore vm with invalid profile tag " + profileId);
                        continue;
                    }

                    final String sourceName = tags.get(AzureConstants.TAG_SOURCE);
                    if (!StringUtil.areEqual(sourceName, details.getSourceName())) {
                        LOG.debug("Ignore vm with invalid source tag " + sourceName);
                        continue;
                    }

                    final AzureInstance instance = new AzureInstance(name);
                    final Promise<Void, Throwable, Void> promise = getInstanceDataAsync(instance, details);
                    promise.fail(new FailCallback<Throwable>() {
                        @Override
                        public void onFail(Throwable result) {
                            LOG.debug(String.format("Failed to receive vm %s data: %s", name, result.getMessage()), result);
                            exceptions.add(result);
                        }
                    });

                    promises.add(promise);
                    instances.put(name, (R) instance);
                }

                if (promises.size() == 0) {
                    deferred.resolve(instances);
                } else {
                    myManager.when(promises.toArray(new Promise[]{})).always(new AlwaysCallback<MultipleResults, OneReject>() {
                        @Override
                        public void onAlways(Promise.State state, MultipleResults resolved, OneReject rejected) {
                            final TypedCloudErrorInfo[] errors = new TypedCloudErrorInfo[exceptions.size()];
                            for (int i = 0; i < exceptions.size(); i++) {
                                errors[i] = TypedCloudErrorInfo.fromException(exceptions.get(i));
                            }

                            image.updateErrors(errors);
                            deferred.resolve(instances);
                        }
                    });
                }
            }
        });

        return deferred.promise();
    }

    private Promise<List<VirtualMachine>, Throwable, Void> getVirtualMachinesAsync() {
        final Deferred<List<VirtualMachine>, Throwable, Void> deferred = new DeferredObject<>();
        myComputeClient.getVirtualMachinesOperations().listAllAsync(new ListOperationCallback<VirtualMachine>() {
            @Override
            public void failure(Throwable t) {
                final String message = "Failed to get list of virtual machines: " + t.getMessage();
                LOG.debug(message, t);
                final CloudException exception = new CloudException(message, t);
                deferred.reject(exception);
            }

            @Override
            public void success(ServiceResponse<List<VirtualMachine>> result) {
                LOG.debug("Received list of virtual machines");
                deferred.resolve(result.getBody());
            }
        });

        return deferred.promise();
    }

    private Promise<Void, Throwable, Void> getInstanceDataAsync(final AzureInstance instance, final AzureCloudImageDetails details) {
        final String name = instance.getName();
        final Promise<Void, Throwable, Void> instanceViewPromise = getVirtualMachineAsync(name, name).then(new DonePipe<VirtualMachine, Void, Throwable, Void>() {
            @Override
            public Promise<Void, Throwable, Void> pipeDone(VirtualMachine machine) {
                LOG.debug(String.format("Received virtual machine %s info", name));

                for (InstanceViewStatus status : machine.getInstanceView().getStatuses()) {
                    final String code = status.getCode();
                    if (code.startsWith(PROVISIONING_STATE)) {
                        instance.setProvisioningState(code.substring(PROVISIONING_STATE.length()));
                        final DateTime dateTime = status.getTime();
                        if (dateTime != null) {
                            instance.setStartDate(dateTime.toDate());
                        }
                    }
                    if (code.startsWith(POWER_STATE)) {
                        instance.setPowerState(code.substring(POWER_STATE.length()));
                    }
                }

                return new DeferredObject<Void, Throwable, Void>().resolve(null);
            }
        });

        final Promise<Void, Throwable, Void> publicIpPromise;
        if (details.getVmPublicIp() && instance.getIpAddress() == null) {
            final String pipName = name + PUBLIC_IP_SUFFIX;
            publicIpPromise = getPublicIpAsync(name, pipName).then(new DonePipe<String, Void, Throwable, Void>() {
                @Override
                public Promise<Void, Throwable, Void> pipeDone(String result) {
                    LOG.debug(String.format("Received public ip %s for virtual machine %s", result, name));

                    if (!StringUtil.isEmpty(result)) {
                        instance.setIpAddress(result);
                    }

                    return new DeferredObject<Void, Throwable, Void>().resolve(null);
                }
            });
        } else {
            publicIpPromise = new DeferredObject<Void, Throwable, Void>().resolve(null);
        }

        return myManager.when(instanceViewPromise, publicIpPromise).then(new DonePipe<MultipleResults, Void, Throwable, Void>() {
            @Override
            public Promise<Void, Throwable, Void> pipeDone(MultipleResults result) {
                return new DeferredObject<Void, Throwable, Void>().resolve(null);
            }
        });
    }

    private Promise<VirtualMachine, Throwable, Void> getVirtualMachineAsync(final String groupId, final String name) {
        final DeferredObject<VirtualMachine, Throwable, Void> deferred = new DeferredObject<>();
        myComputeClient.getVirtualMachinesOperations().getAsync(groupId, name, INSTANCE_VIEW, new ServiceCallback<VirtualMachine>() {
            @Override
            public void failure(Throwable t) {
                final String message = "Failed to get virtual machine info: " + t.getMessage();
                LOG.debug(message, t);
                final CloudException exception = new CloudException(message, t);
                deferred.reject(exception);
            }

            @Override
            public void success(ServiceResponse<VirtualMachine> result) {
                LOG.debug(String.format("Received virtual machine %s info", name));
                deferred.resolve(result.getBody());
            }
        });

        return deferred.promise();
    }

    private Promise<String, Throwable, Void> getPublicIpAsync(final String groupId, final String name) {
        final DeferredObject<String, Throwable, Void> deferred = new DeferredObject<>();
        myNetworkClient.getPublicIPAddressesOperations().getAsync(groupId, name, null, new ServiceCallback<PublicIPAddress>() {
            @Override
            public void failure(Throwable t) {
                final String message = String.format("Failed to get public ip address %s info: %s", name, t.getMessage());
                LOG.debug(message, t);
                final CloudException exception = new CloudException(message, t);
                deferred.reject(exception);
            }

            @Override
            public void success(ServiceResponse<PublicIPAddress> result) {
                LOG.debug(String.format("Received public ip %s for %s", result.getBody().getIpAddress(), name));
                deferred.resolve(result.getBody().getIpAddress());
            }
        });

        return deferred.promise();
    }

    @NotNull
    @Override
    public TypedCloudErrorInfo[] checkImage(@NotNull AzureCloudImage image) {
        final List<Throwable> exceptions = new ArrayList<>();
        final String imageUrl = image.getImageDetails().getImageUrl();
        final Promise<String, Throwable, Void> promise = getVhdOsTypeAsync(imageUrl).fail(new FailCallback<Throwable>() {
            @Override
            public void onFail(Throwable result) {
                LOG.debug("Failed to get os type for vhd " + imageUrl, result);
                exceptions.add(result);
            }
        });

        try {
            promise.waitSafely();
        } catch (InterruptedException e) {
            LOG.debug("Failed to wait for receiving vhd type " + imageUrl, e);
            exceptions.add(e);
        }

        if (exceptions.size() == 0) {
            return new TypedCloudErrorInfo[0];
        }

        final TypedCloudErrorInfo[] errors = new TypedCloudErrorInfo[exceptions.size()];
        for (int i = 0; i < exceptions.size(); i++) {
            errors[i] = TypedCloudErrorInfo.fromException(exceptions.get(i));
        }

        return errors;
    }

    @NotNull
    @Override
    public TypedCloudErrorInfo[] checkInstance(@NotNull AzureCloudInstance instance) {
        return new TypedCloudErrorInfo[0];
    }

    /**
     * Gets a list of VM sizes.
     *
     * @return list of sizes.
     */
    @NotNull
    @Override
    public Promise<List<String>, Throwable, Void> getVmSizesAsync() {
        final DeferredObject<List<String>, Throwable, Void> deferred = new DeferredObject<>();
        myComputeClient.getVirtualMachineSizesOperations().listAsync(myLocation, new ListOperationCallback<VirtualMachineSize>() {
            @Override
            public void failure(Throwable t) {
                final String message = String.format("Failed to get list of vm sizes in location %s: %s", myLocation, t.getMessage());
                LOG.debug(message, t);
                final CloudException exception = new CloudException(message, t);
                deferred.reject(exception);
            }

            @Override
            public void success(ServiceResponse<List<VirtualMachineSize>> result) {
                LOG.debug("Received list of vm sizes in location " + myLocation);

                final List<VirtualMachineSize> vmSizes = result.getBody();
                final Comparator<String> comparator = new AlphaNumericStringComparator();
                Collections.sort(vmSizes, new Comparator<VirtualMachineSize>() {
                    @Override
                    public int compare(VirtualMachineSize o1, VirtualMachineSize o2) {
                        final String size1 = o1.getName();
                        final String size2 = o2.getName();
                        return comparator.compare(size1, size2);
                    }
                });

                final List<String> sizes = new ArrayList<>(vmSizes.size());
                for (VirtualMachineSize vmSize : vmSizes) {
                    sizes.add(vmSize.getName());
                }

                deferred.resolve(sizes);
            }
        });

        return deferred.promise();
    }

    /**
     * Creates a new cloud instance.
     *
     * @param instance is a cloud instance.
     * @param userData is a custom data.
     * @return promise.
     */
    @NotNull
    @Override
    public Promise<Void, Throwable, Void> createVmAsync(@NotNull final AzureCloudInstance instance,
                                                        @NotNull final CloudInstanceUserData userData) {
        final String name = instance.getName();
        final String customData;
        try {
            customData = Base64.encodeBase64String(userData.serialize().getBytes("UTF-8"));
        } catch (Exception e) {
            final String message = String.format("Failed to encode custom data for instance %s: %s", name, e.getMessage());
            LOG.debug(message, e);
            final CloudException exception = new CloudException(message, e);
            return new DeferredObject<Void, Throwable, Void>().reject(exception);
        }

        final AzureCloudImageDetails details = instance.getImage().getImageDetails();
        return createResourceGroupAsync(name, myLocation).then(new DonePipe<Void, Void, Throwable, Void>() {
            @Override
            public Promise<Void, Throwable, Void> pipeDone(Void result) {
                LOG.debug(String.format("Created resource group %s in location %s", name, myLocation));

                final boolean publicIp = details.getVmPublicIp();
                final String templateName = publicIp ? "/templates/vm-template-pip.json" : "/templates/vm-template.json";
                final String templateValue = AzureUtils.getResourceAsString(templateName);

                final Map<String, JsonValue> params = new HashMap<>();
                params.put("imageUrl", new JsonValue(details.getImageUrl()));
                params.put("vmName", new JsonValue(name));
                params.put("networkId", new JsonValue(details.getNetworkId()));
                params.put("subnetName", new JsonValue(details.getSubnetId()));
                params.put("adminUserName", new JsonValue(details.getUsername()));
                params.put("adminPassword", new JsonValue(details.getPassword()));
                params.put("osType", new JsonValue(details.getOsType()));
                params.put("vmSize", new JsonValue(details.getVmSize()));
                params.put("customData", new JsonValue(customData));
                params.put("serverId", new JsonValue(myServerId));
                params.put("profileId", new JsonValue(userData.getProfileId()));
                params.put("sourceId", new JsonValue(details.getSourceName()));

                final String parameters = AzureUtils.serializeObject(params);
                final Deployment deployment = new Deployment();
                deployment.setProperties(new DeploymentProperties());
                deployment.getProperties().setMode(DeploymentMode.INCREMENTAL);
                deployment.getProperties().setTemplate(new RawJsonValue(templateValue));
                deployment.getProperties().setParameters(new RawJsonValue(parameters));

                return createDeploymentAsync(name, name, deployment);
            }
        });
    }

    private Promise<Void, Throwable, Void> createResourceGroupAsync(final String groupId, String location) {
        final DeferredObject<Void, Throwable, Void> deferred = new DeferredObject<>();
        final ResourceGroup parameters = new ResourceGroup();
        parameters.setLocation(location);

        myArmClient.getResourceGroupsOperations().createOrUpdateAsync(groupId, parameters, new ServiceCallback<ResourceGroup>() {
            @Override
            public void failure(Throwable t) {
                final String message = String.format("Failed to create resource group %s: %s", groupId, t.getMessage());
                LOG.debug(message, t);
                final CloudException exception = new CloudException(message, t);
                deferred.reject(exception);
            }

            @Override
            public void success(ServiceResponse<ResourceGroup> result) {
                deferred.resolve(null);
            }
        });

        return deferred.promise();
    }

    private Promise<Void, Throwable, Void> createDeploymentAsync(final String groupId, String deploymentId, Deployment deployment) {
        final DeferredObject<Void, Throwable, Void> deferred = new DeferredObject<>();
        myArmClient.getDeploymentsOperations().createOrUpdateAsync(groupId, deploymentId, deployment, new ServiceCallback<DeploymentExtended>() {
            @Override
            public void failure(Throwable t) {
                final String message = String.format("Failed to create deployment in resource group %s: %s", groupId, t.getMessage());
                LOG.debug(message, t);
                final CloudException exception = new CloudException(message, t);
                deferred.reject(exception);
            }

            @Override
            public void success(ServiceResponse<DeploymentExtended> result) {
                deferred.resolve(null);
            }
        });

        return deferred.promise();
    }

    /**
     * Deletes a cloud instance.
     *
     * @param instance is a cloud instance.
     * @return promise.
     */
    @NotNull
    @Override
    public Promise<Void, Throwable, Void> deleteVmAsync(@NotNull final AzureCloudInstance instance) {
        final String name = instance.getName();
        return deleteResourceGroupAsync(instance.getName()).then(new DonePipe<Void, List<CloudBlob>, Throwable, Void>() {
            @Override
            public Promise<List<CloudBlob>, Throwable, Void> pipeDone(Void result) {
                final String url = instance.getImage().getImageDetails().getImageUrl();
                final URI storageBlobs;
                try {
                    final URI imageUrl = new URI(url);
                    storageBlobs = new URI(imageUrl.getScheme(), imageUrl.getHost(), "/vhds/" + name, null);
                } catch (URISyntaxException e) {
                    final String message = String.format("Failed to parse VHD image URL %s for instance %s: %s", url, name, e.getMessage());
                    LOG.debug(message, e);
                    final CloudException exception = new CloudException(message, e);
                    return new DeferredObject<List<CloudBlob>, Throwable, Void>().reject(exception);
                }

                return getBlobsAsync(storageBlobs);
            }
        }).then(new DonePipe<List<CloudBlob>, Void, Throwable, Void>() {
            @Override
            public Promise<Void, Throwable, Void> pipeDone(List<CloudBlob> blobs) {
                for (CloudBlob blob : blobs) {
                    try {
                        blob.deleteIfExists();
                    } catch (Exception e) {
                        final String message = String.format("Failed to delete blob %s for instance %s: %s", blob.getUri(), name, e.getMessage());
                        LOG.warnAndDebugDetails(message, e);
                    }
                }

                return new DeferredObject<Void, Throwable, Void>().resolve(null);
            }
        });
    }

    private Promise<Void, Throwable, Void> deleteResourceGroupAsync(final String groupId) {
        final DeferredObject<Void, Throwable, Void> deferred = new DeferredObject<>();
        myArmClient.getResourceGroupsOperations().deleteAsync(groupId, new ServiceCallback<Void>() {
            @Override
            public void failure(Throwable t) {
                final String message = String.format("Failed to delete resource group %s: %s", groupId, t.getMessage());
                LOG.debug(message, t);
                final CloudException exception = new CloudException(message, t);
                deferred.reject(exception);
            }

            @Override
            public void success(ServiceResponse<Void> result) {
                LOG.debug(String.format("Resource group %s has been successfully deleted", groupId));
                deferred.resolve(result.getBody());
            }
        });

        return deferred.promise();
    }

    /**
     * Restarts an instance.
     *
     * @param instance is a cloud instance.
     * @return promise.
     */
    @NotNull
    @Override
    public Promise<Void, Throwable, Void> restartVmAsync(@NotNull final AzureCloudInstance instance) {
        final DeferredObject<Void, Throwable, Void> deferred = new DeferredObject<>();
        final String name = instance.getName();
        myComputeClient.getVirtualMachinesOperations().restartAsync(name, name, new ServiceCallback<Void>() {
            @Override
            public void failure(Throwable t) {
                final String message = String.format("Failed to restart virtual machine %s: %s", name, t.getMessage());
                LOG.debug(message, t);
                final CloudException exception = new CloudException(message, t);
                deferred.reject(exception);
            }

            @Override
            public void success(ServiceResponse<Void> result) {
                LOG.debug(String.format("Virtual machine %s has been successfully restarted", name));
                deferred.resolve(result.getBody());
            }
        });

        return deferred.promise();
    }

    /**
     * Gets an OS type of VHD image.
     *
     * @param imageUrl is image URL.
     * @return OS type (Linux, Windows).
     */
    @NotNull
    @Override
    public Promise<String, Throwable, Void> getVhdOsTypeAsync(@NotNull final String imageUrl) {
        final DeferredObject<String, Throwable, Void> deferred = new DeferredObject<>();
        final URI uri;
        try {
            uri = URI.create(imageUrl);
        } catch (Exception e) {
            final String message = String.format("Invalid image URL %s: %s", imageUrl, e.getMessage());
            LOG.debug(message, e);
            final CloudException exception = new CloudException(message, e);
            return new DeferredObject<String, Throwable, Void>().reject(exception);
        }

        getBlobsAsync(uri).then(new DoneCallback<List<CloudBlob>>() {
            @Override
            public void onDone(List<CloudBlob> blobs) {
                if (blobs.size() == 0) {
                    final String message = String.format("VHD file %s not found in storage account", imageUrl);
                    LOG.debug(message);
                    deferred.reject(new CloudException(message));
                    return;
                }
                if (blobs.size() > 1) {
                    LOG.debug("Found more than one blobs for url " + imageUrl);
                    deferred.resolve(null);
                    return;
                }

                final CloudBlob blob = blobs.get(0);
                try {
                    if (!StringUtil.endsWithIgnoreCase(imageUrl, blob.getName())) {
                        LOG.debug(String.format("For url %s found blob with invalid name %s", imageUrl, blob.getName()));
                        deferred.resolve(null);
                        return;
                    }

                    blob.downloadAttributes();
                } catch (Exception e) {
                    final String message = "Failed to access storage blob: " + e.getMessage();
                    LOG.debug(message, e);
                    deferred.reject(new CloudException(message, e));
                    return;
                }

                final Map<String, String> metadata = blob.getMetadata();
                if (!"OSDisk".equals(metadata.get("MicrosoftAzureCompute_ImageType"))) {
                    LOG.debug(String.format("Found blob %s with invalid OSDisk metadata", blob.getUri()));
                    deferred.resolve(null);
                    return;
                }

                if (!"Generalized".equals(metadata.get("MicrosoftAzureCompute_OSState"))) {
                    LOG.debug(String.format("Found blob %s with invalid Generalized metadata", blob.getUri()));
                    deferred.reject(new CloudException("VHD image should be generalized."));
                    return;
                }

                deferred.resolve(metadata.get("MicrosoftAzureCompute_OSType"));
            }
        }, new FailCallback<Throwable>() {
            @Override
            public void onFail(Throwable result) {
                LOG.debug(String.format("Failed to receive blobs for url %s: %s", imageUrl, result.getMessage()));
                deferred.reject(result);
            }
        });

        return deferred.promise();
    }

    private Promise<List<CloudBlob>, Throwable, Void> getBlobsAsync(final URI uri) {
        if (uri.getHost() == null || uri.getPath() == null) {
            return new DeferredObject<List<CloudBlob>, Throwable, Void>()
                    .reject(new CloudException("Invalid URL"));
        }

        final int hostSuffix = uri.getHost().indexOf(".blob.core.windows.net");
        if (hostSuffix <= 0) {
            return new DeferredObject<List<CloudBlob>, Throwable, Void>()
                    .reject(new CloudException("Invalid host name"));
        }

        final String storage = uri.getHost().substring(0, hostSuffix);
        final String filesPrefix = uri.getPath();
        final int slash = filesPrefix.indexOf("/", 1);
        if (slash <= 0) {
            return new DeferredObject<List<CloudBlob>, Throwable, Void>()
                    .reject(new CloudException("File path must include container name"));
        }

        return getStorageAccountAsync(storage).then(new DonePipe<CloudStorageAccount, List<CloudBlob>, Throwable, Void>() {
            @Override
            public Promise<List<CloudBlob>, Throwable, Void> pipeDone(CloudStorageAccount account) {
                final String containerName = filesPrefix.substring(1, slash);
                final CloudBlobContainer container;
                try {
                    container = account.createCloudBlobClient().getContainerReference(containerName);
                } catch (Throwable e) {
                    final String message = String.format("Failed to connect to storage account %s: %s", storage, e.getMessage());
                    LOG.debug(message, e);
                    final CloudException exception = new CloudException(message, e);
                    return new DeferredObject<List<CloudBlob>, Throwable, Void>().reject(exception);
                }

                final String blobName = filesPrefix.substring(slash + 1);
                final List<CloudBlob> blobs = new ArrayList<>();
                try {
                    for (ListBlobItem item : container.listBlobs(blobName)) {
                        blobs.add((CloudBlob) item);
                    }
                } catch (Exception e) {
                    final String message = String.format("Failed to list container's %s blobs: %s", containerName, e.getMessage());
                    LOG.debug(message, e);
                    final CloudException exception = new CloudException(message, e);
                    return new DeferredObject<List<CloudBlob>, Throwable, Void>().reject(exception);
                }

                return new DeferredObject<List<CloudBlob>, Throwable, Void>().resolve(blobs);
            }
        });
    }

    private Promise<CloudStorageAccount, Throwable, Void> getStorageAccountAsync(final String storage) {
        return getStorageAccountsAsync().then(new DonePipe<List<StorageAccount>, StorageAccountKeys, Throwable, Void>() {
            @Override
            public Promise<StorageAccountKeys, Throwable, Void> pipeDone(List<StorageAccount> accounts) {
                final StorageAccount account = CollectionsUtil.findFirst(accounts, new Filter<StorageAccount>() {
                    @Override
                    public boolean accept(@NotNull StorageAccount account) {
                        return account.getName().equalsIgnoreCase(storage);
                    }
                });

                if (account == null) {
                    final String message = String.format("Storage account %s not found", storage);
                    LOG.debug(message);
                    return new DeferredObject<StorageAccountKeys, Throwable, Void>().reject(new CloudException(message));
                }

                if (!account.getLocation().equalsIgnoreCase(myLocation)) {
                    final String message = String.format("VHD image should be located in storage account in the %s region", myLocation);
                    LOG.debug(message);
                    return new DeferredObject<StorageAccountKeys, Throwable, Void>().reject(new CloudException(message));
                }

                final Matcher groupMatcher = RESOURCE_GROUP_PATTERN.matcher(account.getId());
                if (!groupMatcher.find()) {
                    final String message = String.format("Invalid storage account identifier %s", account.getId());
                    LOG.debug(message);
                    return new DeferredObject<StorageAccountKeys, Throwable, Void>().reject(new CloudException(message));
                }

                return getStorageAccountKeysAsync(groupMatcher.group(1), storage);
            }
        }).then(new DonePipe<StorageAccountKeys, CloudStorageAccount, Throwable, Void>() {
            @Override
            public Promise<CloudStorageAccount, Throwable, Void> pipeDone(StorageAccountKeys keys) {
                final DeferredObject<CloudStorageAccount, Throwable, Void> deferred = new DeferredObject<>();
                try {
                    deferred.resolve(new CloudStorageAccount(new StorageCredentialsAccountAndKey(storage, keys.getKey1())));
                } catch (URISyntaxException e) {
                    final String message = String.format("Invalid storage account %s credentials: %s", storage, e.getMessage());
                    LOG.debug(message);
                    final CloudException exception = new CloudException(message, e);
                    deferred.reject(exception);
                }

                return deferred;
            }
        });
    }

    private Promise<List<StorageAccount>, Throwable, Void> getStorageAccountsAsync() {
        final DeferredObject<List<StorageAccount>, Throwable, Void> deferred = new DeferredObject<>();
        myStorageClient.getStorageAccountsOperations().listAsync(new ServiceCallback<List<StorageAccount>>() {
            @Override
            public void failure(Throwable t) {
                final String message = "Failed to get list of storage accounts: " + t.getMessage();
                LOG.debug(message, t);
                final CloudException exception = new CloudException(message, t);
                deferred.reject(exception);
            }

            @Override
            public void success(ServiceResponse<List<StorageAccount>> result) {
                LOG.debug("Received list of storage accounts");
                deferred.resolve(result.getBody());
            }
        });

        return deferred.promise();
    }

    private Promise<StorageAccountKeys, Throwable, Void> getStorageAccountKeysAsync(final String groupName,
                                                                                    final String storageName) {
        final DeferredObject<StorageAccountKeys, Throwable, Void> deferred = new DeferredObject<>();
        myStorageClient.getStorageAccountsOperations().listKeysAsync(groupName, storageName, new ServiceCallback<StorageAccountKeys>() {
            @Override
            public void failure(Throwable t) {
                final String message = String.format("Failed to get storage account %s key: %s", storageName, t.getMessage());
                LOG.debug(message, t);
                final CloudException exception = new CloudException(message, t);
                deferred.reject(exception);
            }

            @Override
            public void success(ServiceResponse<StorageAccountKeys> result) {
                LOG.debug("Received keys for storage account " + storageName);
                deferred.resolve(result.getBody());
            }
        });

        return deferred.promise();
    }

    /**
     * Gets a list of subscriptions.
     *
     * @return subscriptions.
     */
    @NotNull
    @Override
    public Promise<Map<String, String>, Throwable, Void> getSubscriptionsAsync() {
        final DeferredObject<Map<String, String>, Throwable, Void> deferred = new DeferredObject<>();
        mySubscriptionClient.getSubscriptionsOperations().listAsync(new ListOperationCallback<Subscription>() {
            @Override
            public void failure(Throwable t) {
                final String message = "Failed to get list of subscriptions " + t.getMessage();
                LOG.debug(message, t);
                final CloudException exception = new CloudException(message, t);
                deferred.reject(exception);
            }

            @Override
            public void success(ServiceResponse<List<Subscription>> result) {
                LOG.debug("Received list of subscriptions");

                final Map<String, String> subscriptions = new LinkedHashMap<>();
                Collections.sort(result.getBody(), new Comparator<Subscription>() {
                    @Override
                    public int compare(Subscription o1, Subscription o2) {
                        return o1.getDisplayName().compareTo(o2.getDisplayName());
                    }
                });

                for (Subscription subscription : result.getBody()) {
                    subscriptions.put(subscription.getSubscriptionId(), subscription.getDisplayName());
                }

                deferred.resolve(subscriptions);
            }
        });

        return deferred.promise();
    }

    /**
     * Gets a list of locations.
     *
     * @return locations.
     */
    @NotNull
    @Override
    public Promise<Map<String, String>, Throwable, Void> getLocationsAsync(@NotNull final String subscription) {
        final DeferredObject<Map<String, String>, Throwable, Void> deferred = new DeferredObject<>();
        mySubscriptionClient.getSubscriptionsOperations().listLocationsAsync(subscription, new ListOperationCallback<Location>() {
            @Override
            public void failure(Throwable t) {
                final String message = String.format("Failed to get list of locations in subscription %s: %s", subscription, t.getMessage());
                LOG.debug(message, t);
                final CloudException exception = new CloudException(message, t);
                deferred.reject(exception);
            }

            @Override
            public void success(ServiceResponse<List<Location>> result) {
                LOG.debug("Received list of locations in subscription " + subscription);

                final Map<String, String> locations = new LinkedHashMap<>();
                Collections.sort(result.getBody(), new Comparator<Location>() {
                    @Override
                    public int compare(Location o1, Location o2) {
                        return o1.getDisplayName().compareTo(o2.getDisplayName());
                    }
                });

                for (Location location : result.getBody()) {
                    locations.put(location.getName(), location.getDisplayName());
                }

                deferred.resolve(locations);
            }
        });

        return deferred.promise();
    }

    /**
     * Gets a list of networks.
     *
     * @return list of networks.
     */
    @NotNull
    @Override
    public Promise<Map<String, List<String>>, Throwable, Void> getNetworksAsync() {
        final DeferredObject<Map<String, List<String>>, Throwable, Void> deferred = new DeferredObject<>();

        myNetworkClient.getVirtualNetworksOperations().listAllAsync(new ListOperationCallback<VirtualNetwork>() {
            @Override
            public void failure(Throwable t) {
                final String message = "Failed to get list of networks: " + t.getMessage();
                LOG.debug(message, t);
                final CloudException exception = new CloudException(message, t);
                deferred.reject(exception);
            }

            @Override
            public void success(ServiceResponse<List<VirtualNetwork>> result) {
                LOG.debug("Received list of networks");

                final Map<String, List<String>> networks = new LinkedHashMap<>();
                for (VirtualNetwork network : result.getBody()) {
                    if (!network.getLocation().equalsIgnoreCase(myLocation)) continue;

                    final List<String> subNetworks = new ArrayList<>();
                    for (Subnet subnet : network.getSubnets()) {
                        subNetworks.add(subnet.getName());
                    }

                    networks.put(network.getId(), subNetworks);
                }

                deferred.resolve(networks);
            }
        });

        return deferred.promise();
    }

    /**
     * Sets a server identifier.
     *
     * @param serverId identifier.
     */
    public void setServerId(@Nullable final String serverId) {
        myServerId = serverId;
    }

    /**
     * Sets a profile identifier.
     *
     * @param profileId identifier.
     */
    public void setProfileId(@Nullable final String profileId) {
        myProfileId = profileId;
    }

    /**
     * Sets subscription identifier for ARM clients.
     *
     * @param subscriptionId is a an identifier.
     */
    public void setSubscriptionId(@NotNull String subscriptionId) {
        myArmClient.setSubscriptionId(subscriptionId);
        myStorageClient.setSubscriptionId(subscriptionId);
        myComputeClient.setSubscriptionId(subscriptionId);
        myNetworkClient.setSubscriptionId(subscriptionId);
    }

    /**
     * Sets a target location for resources.
     *
     * @param location is a location.
     */
    public void setLocation(@NotNull String location) {
        myLocation = location;
    }
}
