/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.cluster.datastore;

import java.util.ArrayList;
import java.util.List;
import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import org.opendaylight.controller.cluster.datastore.messages.EnableNotification;
import org.opendaylight.controller.cluster.datastore.messages.RegisterChangeListener;
import org.opendaylight.controller.cluster.datastore.messages.RegisterChangeListenerReply;
import org.opendaylight.controller.md.sal.common.api.data.AsyncDataChangeListener;
import org.opendaylight.yangtools.concepts.ListenerRegistration;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DataChangeListenerSupport extends LeaderLocalDelegateFactory<RegisterChangeListener, ListenerRegistration<AsyncDataChangeListener<YangInstanceIdentifier, NormalizedNode<?, ?>>>> {
    private static final Logger LOG = LoggerFactory.getLogger(DataChangeListenerSupport.class);
    private final List<DelayedListenerRegistration> delayedListenerRegistrations = new ArrayList<>();
    private final List<ActorSelection> dataChangeListeners =  new ArrayList<>();

    DataChangeListenerSupport(final Shard shard) {
        super(shard);
    }

    @Override
    void onLeadershipChange(final boolean isLeader) {
        for (ActorSelection dataChangeListener : dataChangeListeners) {
            dataChangeListener.tell(new EnableNotification(isLeader), getSelf());
        }

        if (isLeader) {
            for (DelayedListenerRegistration reg: delayedListenerRegistrations) {
                if(!reg.isClosed()) {
                    reg.setDelegate(createDelegate(reg.getRegisterChangeListener()));
                }
            }

            delayedListenerRegistrations.clear();
        }
    }

    @Override
    void onMessage(final RegisterChangeListener message, final boolean isLeader) {

        LOG.debug("{}: registerDataChangeListener for {}, leader: {}", persistenceId(), message.getPath(), isLeader);

        ListenerRegistration<AsyncDataChangeListener<YangInstanceIdentifier,
                                                     NormalizedNode<?, ?>>> registration;
        if (isLeader) {
            registration = createDelegate(message);
        } else {
            LOG.debug("{}: Shard is not the leader - delaying registration", persistenceId());

            DelayedListenerRegistration delayedReg = new DelayedListenerRegistration(message);
            delayedListenerRegistrations.add(delayedReg);
            registration = delayedReg;
        }

        ActorRef listenerRegistration = createActor(DataChangeListenerRegistration.props(registration));

        LOG.debug("{}: registerDataChangeListener sending reply, listenerRegistrationPath = {} ",
                persistenceId(), listenerRegistration.path());

        tellSender(new RegisterChangeListenerReply(listenerRegistration));
    }

    @Override
    ListenerRegistration<AsyncDataChangeListener<YangInstanceIdentifier, NormalizedNode<?, ?>>> createDelegate(
            final RegisterChangeListener message) {
        ActorSelection dataChangeListenerPath = selectActor(message.getDataChangeListenerPath());

        // Notify the listener if notifications should be enabled or not
        // If this shard is the leader then it will enable notifications else
        // it will not
        dataChangeListenerPath.tell(new EnableNotification(true), getSelf());

        // Now store a reference to the data change listener so it can be notified
        // at a later point if notifications should be enabled or disabled
        dataChangeListeners.add(dataChangeListenerPath);

        AsyncDataChangeListener<YangInstanceIdentifier, NormalizedNode<?, ?>> listener =
                new DataChangeListenerProxy(dataChangeListenerPath);

        LOG.debug("{}: Registering for path {}", persistenceId(), message.getPath());

        return getShard().getDataStore().registerChangeListener(message.getPath(), listener,
                message.getScope());
    }
}
