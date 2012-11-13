package com.rabbitmq.jms.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.jms.Connection;
import javax.jms.ConnectionConsumer;
import javax.jms.ConnectionMetaData;
import javax.jms.Destination;
import javax.jms.ExceptionListener;
import javax.jms.IllegalStateException;
import javax.jms.InvalidClientIDException;
import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.QueueSession;
import javax.jms.ServerSessionPool;
import javax.jms.Session;
import javax.jms.Topic;
import javax.jms.TopicConnection;
import javax.jms.TopicSession;

import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.jms.util.RJMSLogger;
import com.rabbitmq.jms.util.RMQJMSException;

/**
 * Implementation of the {@link Connection}, {@link QueueConnection} and {@link TopicConnection} interfaces.
 * A {@link RMQConnection} object holds a list of {@link RMQSession} objects as well as the actual
 * {link com.rabbitmq.client.Connection} object that represents the TCP connection to the RabbitMQ broker. <br/>
 * This implementation also holds a reference to the executor service that is used by the connection so that we
 * can pause incoming messages.
 *
 */
public class RMQConnection implements Connection, QueueConnection, TopicConnection {

    private static final RJMSLogger LOGGER = new RJMSLogger("RMQConnection");

    /** the TCP connection wrapper to the RabbitMQ broker */
    private final com.rabbitmq.client.Connection rabbitConnection;
    /** Hard coded connection meta data returned in the call {@link #getMetaData()} call */
    private static final ConnectionMetaData connectionMetaData = new RMQConnectionMetaData();
    /** The client ID for this connection */
    private String clientID;
    /** The exception listener - TODO implement usage of exception listener */
    private ExceptionListener exceptionListener;
    /** The list of all {@link RMQSession} objects created by this connection */
    private final List<RMQSession> sessions = Collections.<RMQSession> synchronizedList(new ArrayList<RMQSession>());
    /** value to see if this connection has been closed */
    private volatile boolean closed = false;
    /** atomic flag to pause and unpause the connection consumers (see {@link #start()} and {@link #stop()} methods) */
    private final AtomicBoolean stopped = new AtomicBoolean(true);

    /** maximum time (in ms) to wait for close() to complete */
    private final long terminationTimeout;

    private static ConcurrentHashMap<String, String> CLIENT_IDS = new ConcurrentHashMap<String, String>();

    /** List of all our durable subscriptions so we can track them on a per connection basis (maintained by sessions).*/
    private static final Map<String, RMQMessageConsumer> subscriptions = new ConcurrentHashMap<String, RMQMessageConsumer>();

    /** This is used for JMSCTS test cases, as ClientID should only be configurable right after the connection has been created */
    private volatile boolean canSetClientID = true;

    /**
     * Creates an RMQConnection object.
     * @param rabbitConnection the TCP connection wrapper to the RabbitMQ broker
     * @param terminationTimeout timeout for close in milliseconds
     */
    public RMQConnection(com.rabbitmq.client.Connection rabbitConnection, long terminationTimeout) {
        this.rabbitConnection = rabbitConnection;
        this.terminationTimeout = terminationTimeout;
    }

    private final static long FIFTEEN_SECONDS_MS = 15000;
    /**
     * Creates an RMQConnection object, with default termination timeout of 15 seconds.
     * @param rabbitConnection the TCP connection wrapper to the RabbitMQ broker
     */
    public RMQConnection(com.rabbitmq.client.Connection rabbitConnection) {
        this(rabbitConnection, FIFTEEN_SECONDS_MS);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Session createSession(boolean transacted, int acknowledgeMode) throws JMSException {
        LOGGER.log("createSession");
        canSetClientID = false;
        if (closed) throw new IllegalStateException("Connection is closed");
        RMQSession session = new RMQSession(this, transacted, acknowledgeMode, subscriptions);
        this.sessions.add(session);
        return session;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getClientID() throws JMSException {
        LOGGER.log("getClientID");
        if (closed) throw new IllegalStateException("Connection is closed");
        return this.clientID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setClientID(String clientID) throws JMSException {
        LOGGER.log("setClientID");
        if (!canSetClientID) throw new IllegalStateException("Client ID can only be set right after connection creation");
        if (closed) throw new IllegalStateException("Connection is closed");
        if (this.clientID==null) {
            if (CLIENT_IDS.putIfAbsent(clientID, clientID)==null) {
                this.clientID = clientID;
            } else {
                throw new InvalidClientIDException("A connection with that client ID already exists.["+clientID+"]");
            }
        } else {
            throw new IllegalStateException("Client ID already set.");
        }

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ConnectionMetaData getMetaData() throws JMSException {
        LOGGER.log("getMetaData");
        canSetClientID = false;
        if (closed) throw new IllegalStateException("Connection is closed");
        return connectionMetaData;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ExceptionListener getExceptionListener() throws JMSException {
        LOGGER.log("getExceptionListener");
        canSetClientID = false;
        if (closed) throw new IllegalStateException("Connection is closed");
        return this.exceptionListener;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setExceptionListener(ExceptionListener listener) throws JMSException {
        LOGGER.log("setExceptionListener");
        canSetClientID = false;
        if (closed) throw new IllegalStateException("Connection is closed");
        this.exceptionListener = listener;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() throws JMSException {
        LOGGER.log("start");
        canSetClientID = false;
        if (closed) throw new IllegalStateException("Connection is closed");
        if (stopped.compareAndSet(true, false)) {
            for (RMQSession session : this.sessions) {
                session.resume();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() throws JMSException {
        LOGGER.log("stop");
        canSetClientID = false;
        if (closed) throw new IllegalStateException("Connection is closed");
        if (stopped.compareAndSet(false, true)) {
            for (RMQSession session : this.sessions) {
                session.pause();
            }
        }
    }

    /**
     * @return <code>true</code> if this connection is in a stopped state
     */
    public boolean isStopped() {
        LOGGER.log("isStopped");
        return stopped.get();
    }

    /**
     * <blockquote>
     * <p>This call blocks until a
     * receive or message listener in progress has completed. A blocked message consumer receive call returns null when
     * this message consumer is closed.</p>
     * </blockquote>
     * <p/>{@inheritDoc}
     */
    @Override
    public void close() throws JMSException {
        LOGGER.log("close");
        if (closed) return;

        String cID = getClientID();
        closed = true;

        if (cID != null)
            CLIENT_IDS.remove(cID);

        Exception sessionException = null;
        for (RMQSession session : sessions) {
            try {
                session.internalClose();
            } catch (Exception e) {
                if (null==sessionException) {
                    sessionException = e;
                    e.printStackTrace(); // diagnostics
                }
            }
        }
        this.sessions.clear();

        try {
            this.rabbitConnection.close();
        } catch (AlreadyClosedException x) {
            //nothing to do
        } catch (ShutdownSignalException x) {
            //nothing to do
        } catch (IOException x) {
            throw new RMQJMSException(x);
        }
    }

    com.rabbitmq.client.Connection getRabbitConnection() {
        LOGGER.log("<getRabbitConnection>");
        return this.rabbitConnection;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TopicSession createTopicSession(boolean transacted, int acknowledgeMode) throws JMSException {
        LOGGER.log("createTopicSession");
        if (closed) throw new IllegalStateException("Connection is closed");
        return (TopicSession) this.createSession(transacted, acknowledgeMode);
    }

    /**
     * @throws UnsupportedOperationException - optional method not implemented
     */
    @Override
    public ConnectionConsumer
            createConnectionConsumer(Topic topic, String messageSelector, ServerSessionPool sessionPool, int maxMessages) throws JMSException {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public QueueSession createQueueSession(boolean transacted, int acknowledgeMode) throws JMSException {
        LOGGER.log("createQueueSession");
        if (this.closed) throw new IllegalStateException("Connection is closed");
        return (QueueSession) this.createSession(transacted, acknowledgeMode);
    }

    /**
     * @throws UnsupportedOperationException - optional method not implemented
     */
    @Override
    public ConnectionConsumer createConnectionConsumer(Queue queue,
                                                       String messageSelector,
                                                       ServerSessionPool sessionPool,
                                                       int maxMessages) throws JMSException {
        throw new UnsupportedOperationException();
    }

    /**
     * @throws UnsupportedOperationException - optional method not implemented
     */
    @Override
    public ConnectionConsumer createConnectionConsumer(Destination destination,
                                                       String messageSelector,
                                                       ServerSessionPool sessionPool,
                                                       int maxMessages) throws JMSException {
        throw new UnsupportedOperationException();
    }

    /**
     * @throws UnsupportedOperationException - optional method not implemented
     */
    @Override
    public ConnectionConsumer createDurableConnectionConsumer(Topic topic,
                                                              String subscriptionName,
                                                              String messageSelector,
                                                              ServerSessionPool sessionPool,
                                                              int maxMessages) throws JMSException {
        throw new UnsupportedOperationException();
    }

    /* Internal methods. */

    /** A connection must track all sessions that are created,
     * but when we call {@link RMQSession#close()} we must unregister this
     * session with the connection.
     * This method is called by {@link RMQSession#close()} and should not be called from anywhere else.
     *
     * @param session - the session that is being closed
     */
    void sessionClose(RMQSession session) throws JMSException {
        LOGGER.log("<sessionClose>");
        if (this.sessions.remove(session)) {
            session.internalClose();
        }
    }

    long getTerminationTimeout() {
        LOGGER.log("<getTerminationTimeout>");
        return this.terminationTimeout;
    }
}
