/*
 * CODENVY CONFIDENTIAL
 * __________________
 *
 *  [2012] - [2015] Codenvy, S.A.
 *  All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Codenvy S.A. and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Codenvy S.A.
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Codenvy S.A..
 */
package com.codenvy.api.account.server;

import com.codenvy.api.account.billing.BillingPeriod;
import com.codenvy.api.account.billing.Period;
import com.codenvy.api.account.metrics.MeterBasedStorage;
import com.codenvy.api.account.subscription.ServiceId;

import org.eclipse.che.api.account.server.ResourcesManager;
import org.eclipse.che.api.account.server.dao.Account;
import org.eclipse.che.api.account.server.dao.AccountDao;
import org.eclipse.che.api.account.server.dao.Subscription;
import org.eclipse.che.api.account.shared.dto.UpdateResourcesDescriptor;
import org.eclipse.che.api.core.ConflictException;
import org.eclipse.che.api.core.ForbiddenException;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.notification.EventService;
import org.eclipse.che.api.runner.internal.Constants;
import org.eclipse.che.api.workspace.server.dao.Workspace;
import org.eclipse.che.api.workspace.server.dao.WorkspaceDao;
import org.eclipse.che.dto.server.DtoFactory;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.testng.MockitoTestNGListener;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.eclipse.che.api.account.server.Constants.RESOURCES_LOCKED_PROPERTY;
import static org.eclipse.che.api.workspace.server.Constants.RESOURCES_USAGE_LIMIT_PROPERTY;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

/**
 * Tests for {@link com.codenvy.api.account.server.ResourcesManagerImpl}
 *
 * @author Sergii Leschenko
 * @author Max Shaposhnik
 */
@Listeners(MockitoTestNGListener.class)
public class ResourcesManagerImplTest {
    private static final String ACCOUNT_ID = "accountId";

    private static final Integer MAX_LIMIT           = 4096;
    private static final String  FIRST_WORKSPACE_ID  = "firstWorkspace";
    private static final String  SECOND_WORKSPACE_ID = "secondWorkspace";

    @Mock
    WorkspaceDao             workspaceDao;
    @Mock
    AccountDao               accountDao;
    @Mock
    ResourcesChangesNotifier resourcesChangesNotifier;
    @Mock
    BillingPeriod            billingPeriod;
    @Mock
    MeterBasedStorage        meterBasedStorage;
    @Mock
    EventService             eventService;

    ResourcesManager resourcesManager;

    @Mock
    Period period;

    @BeforeMethod
    public void setUp() throws NotFoundException, ServerException {
        when(billingPeriod.getCurrent()).thenReturn(period);
        when(period.getStartDate()).thenReturn(new Date());

        Map<String, String> firstAttributes = new HashMap<>();
        firstAttributes.put(Constants.RUNNER_MAX_MEMORY_SIZE, "1024");
        Workspace firstWorkspace = new Workspace().withAccountId(ACCOUNT_ID)
                                                  .withId(FIRST_WORKSPACE_ID)
                                                  .withAttributes(firstAttributes);

        Map<String, String> secondAttributes = new HashMap<>();
        secondAttributes.put(Constants.RUNNER_MAX_MEMORY_SIZE, "2048");
        Workspace secondWorkspace = new Workspace().withAccountId(ACCOUNT_ID)
                                                   .withId(SECOND_WORKSPACE_ID)
                                                   .withAttributes(secondAttributes);

        when(workspaceDao.getByAccount(ACCOUNT_ID)).thenReturn(Arrays.asList(firstWorkspace, secondWorkspace));
        when(accountDao.getById(anyString())).thenReturn(new Account().withId(ACCOUNT_ID).withName("accountName"));

        resourcesManager = new ResourcesManagerImpl(MAX_LIMIT, accountDao, workspaceDao, resourcesChangesNotifier, billingPeriod,
                                                    meterBasedStorage, eventService);
    }

    @Test(expectedExceptions = ForbiddenException.class,
            expectedExceptionsMessageRegExp = "Workspace \\w* is not related to account \\w*")
    public void shouldThrowConflictExceptionIfAccountIsNotOwnerOfWorkspace() throws Exception {
        resourcesManager.redistributeResources(ACCOUNT_ID,
                                               Arrays.asList(DtoFactory.getInstance().createDto(UpdateResourcesDescriptor.class)
                                                                       .withWorkspaceId(FIRST_WORKSPACE_ID)
                                                                       .withRunnerRam(1024),
                                                             DtoFactory.getInstance().createDto(UpdateResourcesDescriptor.class)
                                                                       .withWorkspaceId("another_workspace")
                                                                       .withRunnerRam(1024)));
    }

    @Test(expectedExceptions = ConflictException.class,
            expectedExceptionsMessageRegExp = "Missed description of resources for workspace \\w*")
    public void shouldThrowConflictExceptionIfMissedResources() throws Exception {
        resourcesManager.redistributeResources(ACCOUNT_ID,
                                               Arrays.asList(DtoFactory.getInstance().createDto(UpdateResourcesDescriptor.class)
                                                                       .withWorkspaceId(FIRST_WORKSPACE_ID),
                                                             DtoFactory.getInstance().createDto(UpdateResourcesDescriptor.class)
                                                                       .withWorkspaceId(SECOND_WORKSPACE_ID)));
    }


    @Test(expectedExceptions = ConflictException.class,
            expectedExceptionsMessageRegExp = "Size of RAM for workspace \\w* is a negative number")
    public void shouldThrowConflictExceptionIfSizeOfRAMIsNegativeNumber() throws Exception {
        resourcesManager.redistributeResources(ACCOUNT_ID,
                                               Arrays.asList(DtoFactory.getInstance().createDto(UpdateResourcesDescriptor.class)
                                                                       .withWorkspaceId(SECOND_WORKSPACE_ID)
                                                                       .withRunnerRam(-256)));
    }

    @Test(expectedExceptions = ConflictException.class,
            expectedExceptionsMessageRegExp = "Builder timeout for workspace \\w* is a negative number")
    public void shouldThrowConflictExceptionIfSizeOfBuilderTimeoutIsNegativeNumber() throws Exception {
        resourcesManager.redistributeResources(ACCOUNT_ID, Arrays.asList(DtoFactory.getInstance().createDto(UpdateResourcesDescriptor.class)
                                                                                   .withWorkspaceId(SECOND_WORKSPACE_ID)
                                                                                   .withBuilderTimeout(-5)));
    }

    @Test(expectedExceptions = ConflictException.class,
            expectedExceptionsMessageRegExp = "Runner timeout for workspace \\w* is a negative number")
    public void shouldThrowConflictExceptionIfSizeOfRunnerTimeoutIsNegativeNumber() throws Exception {
        resourcesManager.redistributeResources(ACCOUNT_ID,
                                               Arrays.asList(DtoFactory.getInstance().createDto(UpdateResourcesDescriptor.class)
                                                                       .withWorkspaceId(FIRST_WORKSPACE_ID)
                                                                       .withRunnerTimeout(-1), //ok
                                                             DtoFactory.getInstance().createDto(
                                                                     UpdateResourcesDescriptor.class)
                                                                       .withWorkspaceId(SECOND_WORKSPACE_ID)
                                                                       .withRunnerTimeout(-5))); // not ok
    }

    @Test(expectedExceptions = ConflictException.class,
            expectedExceptionsMessageRegExp = "Size of RAM for workspace \\w* has a 4096 MB limit.")
    public void shouldThrowConflictExceptionIfSizeOfRAMIsTooBigForCommunityAccountNumber() throws Exception {
        resourcesManager.redistributeResources(ACCOUNT_ID, Arrays.asList(DtoFactory.getInstance().createDto(UpdateResourcesDescriptor.class)
                                                                                   .withWorkspaceId(SECOND_WORKSPACE_ID)
                                                                                   .withRunnerRam(5000)));
    }

    @Test(expectedExceptions = ConflictException.class,
            expectedExceptionsMessageRegExp = "Resources usage limit for workspace \\w* is a negative number")
    public void shouldThrowConflictExceptionIfValueOfResourcesUsageLimitIsNegativeNumber() throws Exception {
        resourcesManager.redistributeResources(ACCOUNT_ID, Arrays.asList(DtoFactory.getInstance().createDto(UpdateResourcesDescriptor.class)
                                                                                   .withWorkspaceId(SECOND_WORKSPACE_ID)
                                                                                   .withResourcesUsageLimit(-2D)));
    }

    @Test
    public void shouldRedistributeRunnerLimitResources() throws Exception {
        resourcesManager.redistributeResources(ACCOUNT_ID,
                                               Arrays.asList(DtoFactory.getInstance().createDto(UpdateResourcesDescriptor.class)
                                                                       .withWorkspaceId(FIRST_WORKSPACE_ID)
                                                                       .withRunnerTimeout(20),
                                                             DtoFactory.getInstance().createDto(UpdateResourcesDescriptor.class)
                                                                       .withWorkspaceId(SECOND_WORKSPACE_ID)
                                                                       .withRunnerTimeout(20)));

        ArgumentCaptor<Workspace> workspaceArgumentCaptor = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceDao, times(2)).update(workspaceArgumentCaptor.capture());

        for (Workspace workspace : workspaceArgumentCaptor.getAllValues()) {
            assertEquals(workspace.getAttributes().get(Constants.RUNNER_LIFETIME), "20");
        }
    }

    @Test
    public void shouldRedistributeBuilderLimitResources() throws Exception {
        resourcesManager.redistributeResources(ACCOUNT_ID,
                                               Arrays.asList(DtoFactory.getInstance().createDto(UpdateResourcesDescriptor.class)
                                                                       .withWorkspaceId(FIRST_WORKSPACE_ID)
                                                                       .withBuilderTimeout(10),
                                                             DtoFactory.getInstance().createDto(UpdateResourcesDescriptor.class)
                                                                       .withWorkspaceId(SECOND_WORKSPACE_ID)
                                                                       .withBuilderTimeout(10)));

        ArgumentCaptor<Workspace> workspaceArgumentCaptor = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceDao, times(2)).update(workspaceArgumentCaptor.capture());

        for (Workspace workspace : workspaceArgumentCaptor.getAllValues()) {
            assertEquals(workspace.getAttributes().get(org.eclipse.che.api.builder.internal.Constants.BUILDER_EXECUTION_TIME), "10");
        }
    }

    @Test
    public void shouldRedistributeRAMResources() throws Exception {
        resourcesManager.redistributeResources(ACCOUNT_ID,
                                               Arrays.asList(DtoFactory.getInstance().createDto(UpdateResourcesDescriptor.class)
                                                                       .withWorkspaceId(FIRST_WORKSPACE_ID)
                                                                       .withRunnerRam(1024),
                                                             DtoFactory.getInstance().createDto(UpdateResourcesDescriptor.class)
                                                                       .withWorkspaceId(SECOND_WORKSPACE_ID)
                                                                       .withRunnerRam(2048)));

        ArgumentCaptor<Workspace> workspaceArgumentCaptor = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceDao, times(2)).update(workspaceArgumentCaptor.capture());

        for (Workspace workspace : workspaceArgumentCaptor.getAllValues()) {
            switch (workspace.getId()) {
                case FIRST_WORKSPACE_ID:
                    assertEquals(workspace.getAttributes().get(Constants.RUNNER_MAX_MEMORY_SIZE), "1024");
                    break;
                case SECOND_WORKSPACE_ID:
                    assertEquals(workspace.getAttributes().get(Constants.RUNNER_MAX_MEMORY_SIZE), "2048");
                    break;
            }
        }

        verify(resourcesChangesNotifier).publishTotalMemoryChangedEvent(eq(FIRST_WORKSPACE_ID), eq("1024"));
        verify(resourcesChangesNotifier).publishTotalMemoryChangedEvent(eq(SECOND_WORKSPACE_ID), eq("2048"));
    }

    @Test
    public void shouldBeAbleToAddMemoryWithoutLimitationForAccountWithNotCommunitySubscription() throws Exception {
        //given
        Subscription subscription = Mockito.mock(Subscription.class);
        when(subscription.getPlanId()).thenReturn("Super-Pupper-Plan");
        when(accountDao.getActiveSubscription(eq(ACCOUNT_ID), eq(ServiceId.SAAS))).thenReturn(subscription);

        //when
        resourcesManager.redistributeResources(ACCOUNT_ID, Arrays.asList(DtoFactory.getInstance().createDto(UpdateResourcesDescriptor.class)
                                                                                   .withWorkspaceId(FIRST_WORKSPACE_ID)
                                                                                   .withRunnerRam(Integer.MAX_VALUE),
                                                                         DtoFactory.getInstance().createDto(UpdateResourcesDescriptor.class)
                                                                                   .withWorkspaceId(SECOND_WORKSPACE_ID)
                                                                                   .withRunnerRam(0)));

        //then
        ArgumentCaptor<Workspace> workspaceArgumentCaptor = ArgumentCaptor.forClass(Workspace.class);
        verify(workspaceDao, times(2)).update(workspaceArgumentCaptor.capture());

        for (Workspace workspace : workspaceArgumentCaptor.getAllValues()) {
            switch (workspace.getId()) {
                case FIRST_WORKSPACE_ID:
                    assertEquals(workspace.getAttributes().get(Constants.RUNNER_MAX_MEMORY_SIZE), String.valueOf(Integer.MAX_VALUE));
                    break;
                case SECOND_WORKSPACE_ID:
                    assertEquals(workspace.getAttributes().get(Constants.RUNNER_MAX_MEMORY_SIZE), "0");
                    break;
            }
        }

        verify(resourcesChangesNotifier).publishTotalMemoryChangedEvent(eq(FIRST_WORKSPACE_ID), eq(String.valueOf(Integer.MAX_VALUE)));
        verify(resourcesChangesNotifier).publishTotalMemoryChangedEvent(eq(SECOND_WORKSPACE_ID), eq("0"));
    }

    @Test
    public void shouldRemoveResourcesUsageLimitButDoNotRemoveWorkspaceLockIfNewValueEqualsToMinus1AndAccountIsLocked() throws Exception {
        Map<String, String> attributes = new HashMap<>();
        attributes.put(RESOURCES_LOCKED_PROPERTY, "true");
        when(accountDao.getById(eq(ACCOUNT_ID))).thenReturn(new Account().withAttributes(attributes));

        Map<String, String> firstAttributes = new HashMap<>();
        firstAttributes.put(RESOURCES_USAGE_LIMIT_PROPERTY, "1");
        firstAttributes.put(RESOURCES_LOCKED_PROPERTY, "true");
        Workspace firstWorkspace = new Workspace().withAccountId(ACCOUNT_ID)
                                                  .withId(FIRST_WORKSPACE_ID)
                                                  .withAttributes(firstAttributes);

        when(workspaceDao.getByAccount(ACCOUNT_ID)).thenReturn(Collections.singletonList(firstWorkspace));

        resourcesManager.redistributeResources(ACCOUNT_ID, Collections.singletonList(DtoFactory.getInstance()
                                                                                               .createDto(UpdateResourcesDescriptor.class)
                                                                                               .withWorkspaceId(FIRST_WORKSPACE_ID)
                                                                                               .withResourcesUsageLimit(-1D)));

        verify(workspaceDao).update(argThat(new ArgumentMatcher<Workspace>() {
            @Override
            public boolean matches(Object o) {
                Workspace workspace = (Workspace)o;
                return !workspace.getAttributes().containsKey(RESOURCES_USAGE_LIMIT_PROPERTY)
                        && workspace.getAttributes().containsKey(RESOURCES_LOCKED_PROPERTY);
            }
        }));
    }

    @Test
    public void shouldRemoveResourcesUsageLimitAndRemoveWorkspaceLockIfNewValueEqualsToMinus1AndAccountIsNotLocked() throws Exception {
        when(accountDao.getById(eq(ACCOUNT_ID))).thenReturn(new Account());

        Map<String, String> firstAttributes = new HashMap<>();
        firstAttributes.put(RESOURCES_USAGE_LIMIT_PROPERTY, "1");
        firstAttributes.put(RESOURCES_LOCKED_PROPERTY, "true");
        Workspace firstWorkspace = new Workspace().withAccountId(ACCOUNT_ID)
                                                  .withId(FIRST_WORKSPACE_ID)
                                                  .withAttributes(firstAttributes);

        when(workspaceDao.getByAccount(ACCOUNT_ID)).thenReturn(Collections.singletonList(firstWorkspace));

        resourcesManager.redistributeResources(ACCOUNT_ID, Collections.singletonList(DtoFactory.getInstance()
                                                                                               .createDto(UpdateResourcesDescriptor.class)
                                                                                               .withWorkspaceId(FIRST_WORKSPACE_ID)
                                                                                               .withResourcesUsageLimit(-1D)));

        verify(workspaceDao).update(argThat(new ArgumentMatcher<Workspace>() {
            @Override
            public boolean matches(Object o) {
                Workspace workspace = (Workspace)o;
                return !workspace.getAttributes().containsKey(RESOURCES_USAGE_LIMIT_PROPERTY)
                       && !workspace.getAttributes().containsKey(RESOURCES_LOCKED_PROPERTY);
            }
        }));
    }

    @Test
    public void shouldUnlockWorkspaceIfNewResourcesUsageMoreThanUsedResourcesAndAccountIsNotLocked() throws Exception {
        when(accountDao.getById(eq(ACCOUNT_ID))).thenReturn(new Account());
        when(meterBasedStorage.getUsedMemoryByWorkspace(eq(FIRST_WORKSPACE_ID), anyLong(), anyLong())).thenReturn(25D);

        resourcesManager.redistributeResources(ACCOUNT_ID, Collections.singletonList(DtoFactory.getInstance()
                                                                                               .createDto(UpdateResourcesDescriptor.class)
                                                                                               .withWorkspaceId(FIRST_WORKSPACE_ID)
                                                                                               .withResourcesUsageLimit(50D)));
        verify(workspaceDao).update(argThat(new ArgumentMatcher<Workspace>() {
            @Override
            public boolean matches(Object o) {
                final Workspace workspace = (Workspace)o;
                return FIRST_WORKSPACE_ID.equals(workspace.getId())
                       && !workspace.getAttributes().containsKey(RESOURCES_LOCKED_PROPERTY);
            }
        }));
    }

    @Test
    public void shouldDoNotUnlockWorkspaceIfNewResourcesUsageMoreThanUsedResourcesAndAccountIsLocked() throws Exception {
        Map<String, String> attributes = new HashMap<>();
        attributes.put(RESOURCES_LOCKED_PROPERTY, "true");
        when(accountDao.getById(eq(ACCOUNT_ID))).thenReturn(new Account().withAttributes(attributes));
        when(meterBasedStorage.getUsedMemoryByWorkspace(eq(FIRST_WORKSPACE_ID), anyLong(), anyLong())).thenReturn(25D);

        Map<String, String> firstAttributes = new HashMap<>();
        firstAttributes.put(RESOURCES_LOCKED_PROPERTY, "true");
        Workspace firstWorkspace = new Workspace().withAccountId(ACCOUNT_ID)
                                                  .withId(FIRST_WORKSPACE_ID)
                                                  .withAttributes(firstAttributes);

        when(workspaceDao.getByAccount(ACCOUNT_ID)).thenReturn(Collections.singletonList(firstWorkspace));

        resourcesManager.redistributeResources(ACCOUNT_ID, Collections.singletonList(DtoFactory.getInstance()
                                                                                               .createDto(UpdateResourcesDescriptor.class)
                                                                                               .withWorkspaceId(FIRST_WORKSPACE_ID)
                                                                                               .withResourcesUsageLimit(50D)));
        verify(workspaceDao).update(argThat(new ArgumentMatcher<Workspace>() {
            @Override
            public boolean matches(Object o) {
                final Workspace workspace = (Workspace)o;
                return FIRST_WORKSPACE_ID.equals(workspace.getId())
                       && workspace.getAttributes().containsKey(RESOURCES_LOCKED_PROPERTY);
            }
        }));
    }

    @Test
    public void shouldLockWorkspaceIfNewResourcesUsageLessThanUsedResources() throws Exception {
        when(meterBasedStorage.getUsedMemoryByWorkspace(eq(FIRST_WORKSPACE_ID), anyLong(), anyLong())).thenReturn(50D);

        resourcesManager.redistributeResources(ACCOUNT_ID, Collections.singletonList(DtoFactory.getInstance()
                                                                                               .createDto(UpdateResourcesDescriptor.class)
                                                                                               .withWorkspaceId(FIRST_WORKSPACE_ID)
                                                                                               .withResourcesUsageLimit(25D)));
        verify(workspaceDao).update(argThat(new ArgumentMatcher<Workspace>() {
            @Override
            public boolean matches(Object o) {
                final Workspace workspace = (Workspace)o;
                return FIRST_WORKSPACE_ID.equals(workspace.getId())
                       && "true".equals(workspace.getAttributes().get(RESOURCES_LOCKED_PROPERTY));
            }
        }));
    }
}