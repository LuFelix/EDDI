package io.sls.persistence.interceptor;

import io.sls.memory.descriptor.IConversationDescriptorStore;
import io.sls.memory.descriptor.model.ConversationDescriptor;
import io.sls.persistence.IDescriptorStore;
import io.sls.persistence.IResourceStore;
import io.sls.resources.rest.descriptor.model.ResourceDescriptor;
import io.sls.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.resources.rest.method.PATCH;
import io.sls.runtime.DependencyInjector;
import io.sls.runtime.ThreadContext;
import io.sls.testing.descriptor.ITestCaseDescriptorStore;
import io.sls.testing.descriptor.model.TestCaseDescriptor;
import io.sls.user.IUserStore;
import io.sls.user.impl.utilities.UserUtilities;
import io.sls.utilities.RestUtilities;
import io.sls.utilities.SecurityUtilities;
import org.jboss.resteasy.annotations.interception.ServerInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.DELETE;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.security.Principal;
import java.util.Date;

/**
 * User: jarisch
 * Date: 27.08.12
 * Time: 13:13
 */

@Provider
@ServerInterceptor
public class DocumentDescriptorInterceptor implements ContainerResponseFilter {
    private static final String METHOD_NAME_UPDATE_DESCRIPTOR = "updateDescriptor";
    private static final String METHOD_NAME_UPDATE_PERMISSIONS = "updatePermissions";
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final IUserStore userStore;

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Context
    private ResourceInfo resourceInfo;
    private final DependencyInjector injector;

    public DocumentDescriptorInterceptor() {
        injector = DependencyInjector.getInstance();
        this.userStore = injector.getInstance(IUserStore.class);
        this.documentDescriptorStore = injector.getInstance(IDocumentDescriptorStore.class);
        this.conversationDescriptorStore = injector.getInstance(IConversationDescriptorStore.class);
    }
    
    @Override
    public void filter(ContainerRequestContext contextRequest, ContainerResponseContext contextResponse) throws IOException {
        try {
            int httpStatus = contextResponse.getStatus();
            Object entity = contextResponse.getEntity();
            Method resourceMethod = resourceInfo.getResourceMethod();
            if (resourceMethod != null && (isPUT(resourceMethod) || isPATCH(resourceMethod) || isPOST(resourceMethod) || isDELETE(resourceMethod)) && httpStatus >= 200 && httpStatus < 300) {
                if (entity != null) {
                    String createdResourceURIString = entity.toString();
                    URI createdResourceURI = URI.create(createdResourceURIString);
                    IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(createdResourceURI);
                    Principal userPrincipal = SecurityUtilities.getPrincipal(ThreadContext.getSubject());
                    URI userURI = UserUtilities.getUserURI(userStore, userPrincipal);

                    if (isPOST(resourceMethod)) {
                        // the resource was created successfully
                        if (httpStatus == 201) {
                            if (createdResourceURIString.startsWith("resource://io.sls.testcases")) {
                                injector.getInstance(ITestCaseDescriptorStore.class).createDescriptor(resourceId.getId(), resourceId.getVersion(), createTestCaseDescriptor(createdResourceURI, userURI));
                            } else if (createdResourceURIString.startsWith("resource://io.sls.conversation")) {
                                conversationDescriptorStore.createDescriptor(resourceId.getId(), resourceId.getVersion(), createConversationDescriptor(createdResourceURI, userURI));
                            } else {
                                documentDescriptorStore.createDescriptor(resourceId.getId(), resourceId.getVersion(), createDocumentDescriptor(createdResourceURI, userURI));
                            }
                        }

                        return;
                    }

                    if ((isPUT(resourceMethod) || isPATCH(resourceMethod)) && !isUpdateDescriptor(resourceMethod) && !isUpdatePermissions(resourceMethod)) {
                        IDescriptorStore descriptorStore = getDescriptorStore(createdResourceURIString);
                        ResourceDescriptor resourceDescriptor = (ResourceDescriptor) descriptorStore.readDescriptor(resourceId.getId(), resourceId.getVersion() - 1);
                        resourceDescriptor.setLastModifiedOn(new Date(System.currentTimeMillis()));
                        resourceDescriptor.setLastModifiedBy(UserUtilities.getUserURI(userStore, SecurityUtilities.getPrincipal(ThreadContext.getSubject())));
                        resourceDescriptor.setResource(createNewVersionOfResource(resourceDescriptor.getResource(), resourceId.getVersion()));
                        descriptorStore.updateDescriptor(resourceId.getId(), resourceId.getVersion() - 1, resourceDescriptor);
                    }
                }

                if (isDELETE(resourceMethod)) {
                    String currentResourceURI = (String) ThreadContext.get("currentResourceURI");
                    IDescriptorStore descriptorStore = getDescriptorStore(currentResourceURI);
                    IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(URI.create(currentResourceURI));
                    ResourceDescriptor resourceDescriptor = (ResourceDescriptor) descriptorStore.readDescriptor(resourceId.getId(), resourceId.getVersion());
                    resourceDescriptor.setDeleted(true);
                    descriptorStore.setDescriptor(resourceId.getId(), resourceId.getVersion(), resourceDescriptor);
                }
            }
        } catch (IResourceStore.ResourceStoreException |
                IResourceStore.ResourceNotFoundException |
                IResourceStore.ResourceModifiedException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    private IDescriptorStore getDescriptorStore(String createdResourceURIString) {
        IDescriptorStore descriptorStore;
        if (createdResourceURIString.startsWith("resource://io.sls.testcases")) {
            descriptorStore = injector.getInstance(ITestCaseDescriptorStore.class);
        } else if (createdResourceURIString.startsWith("resource://io.sls.conversation")) {
            descriptorStore = conversationDescriptorStore;
        } else {
            descriptorStore = documentDescriptorStore;
        }
        return descriptorStore;
    }

    private TestCaseDescriptor createTestCaseDescriptor(URI resource, URI author) {
        Date current = new Date(System.currentTimeMillis());

        TestCaseDescriptor descriptor = new TestCaseDescriptor();
        descriptor.setName("");
        descriptor.setDescription("");
        descriptor.setResource(resource);
        descriptor.setCreatedBy(author);
        descriptor.setCreatedOn(current);
        descriptor.setLastModifiedOn(current);
        descriptor.setLastModifiedBy(author);

        return descriptor;
    }

    private DocumentDescriptor createDocumentDescriptor(URI resource, URI author) {
        Date current = new Date(System.currentTimeMillis());

        DocumentDescriptor descriptor = new DocumentDescriptor();
        descriptor.setResource(resource);
        descriptor.setName("");
        descriptor.setDescription("");
        descriptor.setCreatedBy(author);
        descriptor.setCreatedOn(current);
        descriptor.setLastModifiedOn(current);
        descriptor.setLastModifiedBy(author);

        return descriptor;
    }

    private ConversationDescriptor createConversationDescriptor(URI resource, URI user) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        ConversationDescriptor conversationDescriptor = new ConversationDescriptor();
        conversationDescriptor.setResource(resource);
        Date createdOn = new Date(System.currentTimeMillis());
        conversationDescriptor.setCreatedOn(createdOn);
        conversationDescriptor.setLastModifiedOn(createdOn);
        conversationDescriptor.setCreatedBy(user);
        conversationDescriptor.setLastModifiedBy(user);
        conversationDescriptor.setViewState(ConversationDescriptor.ViewState.UNSEEN);
        return conversationDescriptor;
    }

    private URI createNewVersionOfResource(final URI resource, Integer version) {
        String resourceURIString = resource.toString();
        if (resourceURIString.contains("version")) {
            resourceURIString = resourceURIString.substring(0, resourceURIString.lastIndexOf("=") + 1);
            resourceURIString += version;
        } else {
            resourceURIString += "?version=" + version;
        }

        return URI.create(resourceURIString);
    }

    private boolean isUpdatePermissions(Method resourceMethod) {
        return resourceMethod.getName().equals(METHOD_NAME_UPDATE_PERMISSIONS);
    }

    private boolean isUpdateDescriptor(Method resourceMethod) {
        return resourceMethod.getName().equals(METHOD_NAME_UPDATE_DESCRIPTOR);
    }

    private boolean isPUT(Method resourceMethod) {
        return resourceMethod.isAnnotationPresent(PUT.class);
    }

    private boolean isPATCH(Method resourceMethod) {
        return resourceMethod.isAnnotationPresent(PATCH.class);
    }

    private boolean isPOST(Method resourceMethod) {
        return resourceMethod.isAnnotationPresent(POST.class);
    }

    public boolean isDELETE(Method resourceMethod) {
        return resourceMethod.isAnnotationPresent(DELETE.class);
    }
}