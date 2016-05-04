package org.terracotta.clientcommunicator.support;

import org.terracotta.entity.EntityClientEndpoint;
import org.terracotta.entity.EntityMessage;
import org.terracotta.entity.EntityResponse;
import org.terracotta.entity.MessageCodecException;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author vmad
 */
public class ClientCommunicatorClientManagerImpl<M extends EntityMessage, R extends EntityResponse> implements ClientCommunicatorClientManager<M, R> {

    private final EntityClientEndpoint<M, R> entityClientEndpoint;
    private final ClientCommunicatorMessageFactory<M, R> clientCommunicatorMessageFactory;
    private final ConcurrentMap<Integer, AtomicBoolean> monitors = new ConcurrentHashMap<Integer, AtomicBoolean>();

    public ClientCommunicatorClientManagerImpl(EntityClientEndpoint<M, R> entityClientEndpoint, ClientCommunicatorMessageFactory<M, R> clientCommunicatorMessageFactory) {
        this.entityClientEndpoint = entityClientEndpoint;
        this.clientCommunicatorMessageFactory = clientCommunicatorMessageFactory;
    }

    @Override
    public void handleInvokeResponse(R response) {
        try {
            ClientCommunicatorRequest clientCommunicatorRequest = ClientCommunicatorRequestCodec.deserialize(clientCommunicatorMessageFactory.extractBytesFromResponse(response));

            int requestSequenceNumber = clientCommunicatorRequest.getRequestSequenceNumber();
            if(clientCommunicatorRequest.getRequestType() != ClientCommunicatorRequestType.CLIENT_WAIT) {
                throw new RuntimeException("Received Wrong ClientCommunicatorRequestType in invokeResponse: expected - " + ClientCommunicatorRequestType.CLIENT_WAIT + ", got - " + clientCommunicatorRequest.getRequestType());
            }
            AtomicBoolean last = monitors.putIfAbsent(requestSequenceNumber, new AtomicBoolean());
            if(last == null) {
                AtomicBoolean monitor = monitors.get(requestSequenceNumber);
                while (!monitor.get()) {
                    synchronized (monitor) {
                        monitor.wait();
                    }
                }
            }
            monitors.remove(requestSequenceNumber);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (MessageCodecException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void handleClientCommunicatorMessage(byte[] message, ClientCommunicatorMessageHandler clientCommunicatorMessageHandler) {
        ClientCommunicatorRequest clientCommunicatorRequest = ClientCommunicatorRequestCodec.deserialize(message);;
        switch (clientCommunicatorRequest.getRequestType()) {
            case ACK:
                clientCommunicatorMessageHandler.handleMessage(clientCommunicatorRequest.getMsgBytes());
                try {
                    entityClientEndpoint.beginInvoke().message(clientCommunicatorMessageFactory.createEntityMessage(ByteBuffer.allocate(4).putInt(clientCommunicatorRequest.getRequestSequenceNumber()).array())).invoke();
                } catch (MessageCodecException e) {
                    throw new RuntimeException(e);
                }
                break;

            case NO_ACK:
                clientCommunicatorMessageHandler.handleMessage(clientCommunicatorRequest.getMsgBytes());
                break;

            case REQUEST_COMPLETE:
                int requestSequenceNumber = clientCommunicatorRequest.getRequestSequenceNumber();
                AtomicBoolean last = monitors.putIfAbsent(requestSequenceNumber, new AtomicBoolean());
                if(last != null) {
                    AtomicBoolean monitor = monitors.get(requestSequenceNumber);
                    synchronized (monitor) {
                        monitor.compareAndSet(false, true);
                        monitor.notifyAll();
                    }
                }
                break;

            default:
                throw new IllegalArgumentException("unexpected/unknown ClientCommunicatorRequestType: " + clientCommunicatorRequest.getRequestType());
        }
    }
}
