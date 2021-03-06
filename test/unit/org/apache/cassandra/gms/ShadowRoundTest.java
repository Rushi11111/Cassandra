/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.apache.cassandra.gms;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.cassandra.dht.IPartitioner;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.locator.IEndpointSnitch;
import org.apache.cassandra.locator.PropertyFileSnitch;
import org.apache.cassandra.net.MessageIn;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.net.MockMessagingService;
import org.apache.cassandra.net.MockMessagingSpy;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.FBUtilities;

import static org.apache.cassandra.net.MockMessagingService.verb;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ShadowRoundTest
{
    private static final Logger logger = LoggerFactory.getLogger(ShadowRoundTest.class);

    @BeforeClass
    public static void setUp() throws ConfigurationException
    {
        System.setProperty("cassandra.config", "cassandra-seeds.yaml");

        DatabaseDescriptor.daemonInitialization();
        IEndpointSnitch snitch = new PropertyFileSnitch();
        DatabaseDescriptor.setEndpointSnitch(snitch);
        Keyspace.setInitialized();
    }

    @After
    public void cleanup()
    {
        MockMessagingService.cleanup();
    }

    @Test
    public void testDelayedResponse()
    {
        Gossiper.instance.buildSeedsList();
        int noOfSeeds = Gossiper.instance.seeds.size();

        final AtomicBoolean ackSend = new AtomicBoolean(false);
        MockMessagingSpy spySyn = MockMessagingService.when(verb(MessagingService.Verb.GOSSIP_DIGEST_SYN))
                .respondN((msgOut, to) ->
                {
                    // ACK once to finish shadow round, then busy-spin until gossiper has been enabled
                    // and then reply with remaining ACKs from other seeds
                    if (!ackSend.compareAndSet(false, true))
                    {
                        while (!Gossiper.instance.isEnabled()) ;
                    }

                    HeartBeatState hb = new HeartBeatState(123, 456);
                    EndpointState state = new EndpointState(hb);
                    GossipDigestAck payload = new GossipDigestAck(
                            Collections.singletonList(new GossipDigest(to, hb.getGeneration(), hb.getHeartBeatVersion())),
                            Collections.singletonMap(to, state));

                    logger.debug("Simulating digest ACK reply");
                    return MessageIn.create(to, payload, Collections.emptyMap(), MessagingService.Verb.GOSSIP_DIGEST_ACK, MessagingService.current_version);
                }, noOfSeeds);

        // GossipDigestAckVerbHandler will send ack2 for each ack received (after the shadow round)
        MockMessagingSpy spyAck2 = MockMessagingService.when(verb(MessagingService.Verb.GOSSIP_DIGEST_ACK2)).dontReply();

        // Migration request messages should not be emitted during shadow round
        MockMessagingSpy spyMigrationReq = MockMessagingService.when(verb(MessagingService.Verb.MIGRATION_REQUEST)).dontReply();

        try
        {
            StorageService.instance.initServer();
        }
        catch (Exception e)
        {
            assertEquals("Unable to contact any seeds!", e.getMessage());
        }

        // we expect one SYN for each seed during shadow round + additional SYNs after gossiper has been enabled
        assertTrue(spySyn.messagesIntercepted() > noOfSeeds);

        // we don't expect to emit any GOSSIP_DIGEST_ACK2 or MIGRATION_REQUEST messages
        assertEquals(0, spyAck2.messagesIntercepted());
        assertEquals(0, spyMigrationReq.messagesIntercepted());
    }

    @Test
    public void testBadAckInShadow()
    {
        final AtomicBoolean ackSend = new AtomicBoolean(false);
        MockMessagingSpy spySyn = MockMessagingService.when(verb(MessagingService.Verb.GOSSIP_DIGEST_SYN))
                .respondN((msgOut, to) ->
                {
                    // ACK with bad data in shadow round
                    if (!ackSend.compareAndSet(false, true))
                    {
                        while (!Gossiper.instance.isEnabled()) ;
                    }
                    InetAddress junkaddr;
                    try
                    {
                        junkaddr = InetAddress.getByName("1.1.1.1");
                    }
                    catch (UnknownHostException e)
                    {
                        throw new RuntimeException(e);
                    }

                    HeartBeatState hb = new HeartBeatState(123, 456);
                    EndpointState state = new EndpointState(hb);
                    List<GossipDigest> gDigests = new ArrayList<GossipDigest>();
                    gDigests.add(new GossipDigest(FBUtilities.getBroadcastAddress(), hb.getGeneration(), hb.getHeartBeatVersion()));
                    gDigests.add(new GossipDigest(junkaddr, hb.getGeneration(), hb.getHeartBeatVersion()));
                    Map<InetAddress, EndpointState> smap = new HashMap<InetAddress, EndpointState>()
                    {
                        {
                            put(FBUtilities.getBroadcastAddress(), state);
                            put(junkaddr, state);
                        }
                    };
                    GossipDigestAck payload = new GossipDigestAck(gDigests, smap);

                    logger.debug("Simulating bad digest ACK reply");
                    return MessageIn.create(to, payload, Collections.emptyMap(), MessagingService.Verb.GOSSIP_DIGEST_ACK, MessagingService.current_version);
                }, 1);

        System.setProperty(Config.PROPERTY_PREFIX + "auto_bootstrap", "false");
        try
        {
            StorageService.instance.checkForEndpointCollision(SystemKeyspace.getOrInitializeLocalHostId(), SystemKeyspace.loadHostIds().keySet());
        }
        catch (Exception e)
        {
            assertEquals("Unable to gossip with any peers", e.getMessage());
        }
        System.clearProperty(Config.PROPERTY_PREFIX + "auto_bootstrap");
    }

    @Test
    public void testPreviouslyAssassinatedInShadow()
    {
        final AtomicBoolean ackSend = new AtomicBoolean(false);
        MockMessagingSpy spySyn = MockMessagingService.when(verb(MessagingService.Verb.GOSSIP_DIGEST_SYN))
                .respondN((msgOut, to) ->
                {
                    // ACK with self assassinated in shadow round
                    if (!ackSend.compareAndSet(false, true))
                    {
                        while (!Gossiper.instance.isEnabled()) ;
                    }
                    HeartBeatState hb = new HeartBeatState(123, 456);
                    EndpointState state = new EndpointState(hb);
                    state.addApplicationState(ApplicationState.STATUS,
                            new VersionedValue.VersionedValueFactory(DatabaseDescriptor.getPartitioner()).left(
                                    Collections.singletonList(DatabaseDescriptor.getPartitioner().getRandomToken()), 1L));
                    GossipDigestAck payload = new GossipDigestAck(
                        Collections.singletonList(new GossipDigest(FBUtilities.getBroadcastAddress(), hb.getGeneration(), hb.getHeartBeatVersion())),
                        Collections.singletonMap(FBUtilities.getBroadcastAddress(), state));

                    logger.debug("Simulating bad digest ACK reply");
                    return MessageIn.create(to, payload, Collections.emptyMap(), MessagingService.Verb.GOSSIP_DIGEST_ACK, MessagingService.current_version);
                }, 1);


        System.setProperty(Config.PROPERTY_PREFIX + "auto_bootstrap", "false");
        StorageService.instance.checkForEndpointCollision(SystemKeyspace.getOrInitializeLocalHostId(), SystemKeyspace.loadHostIds().keySet());
        System.clearProperty(Config.PROPERTY_PREFIX + "auto_bootstrap");
    }

}
