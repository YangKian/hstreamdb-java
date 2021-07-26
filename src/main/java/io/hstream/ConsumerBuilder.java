package io.hstream;

import io.hstream.impl.ConsumerImpl;

public class ConsumerBuilder {

  private HStreamApiGrpc.HStreamApiStub grpcStub;
  private HStreamApiGrpc.HStreamApiBlockingStub grpcBlockingStub;
  private String subscription;
  private String streamName;
  private long pollTimeoutMs = 3000;
  private int maxPollRecords = 500;

  public ConsumerBuilder(
      HStreamApiGrpc.HStreamApiStub grpcStub,
      HStreamApiGrpc.HStreamApiBlockingStub grpcBlockingStub) {
    this.grpcStub = grpcStub;
    this.grpcBlockingStub = grpcBlockingStub;
  }

  public ConsumerBuilder subscription(String subscription) {
    this.subscription = subscription;
    return this;
  }

  public ConsumerBuilder stream(String stream) {
    this.streamName = stream;
    return this;
  }

  public ConsumerBuilder pollTimeoutMs(long timeoutMs) {
    this.pollTimeoutMs = timeoutMs;
    return this;
  }

  public ConsumerBuilder maxPollRecords(int maxPollRecords) {
    this.maxPollRecords = maxPollRecords;
    return this;
  }

  public Consumer build() {
    return new ConsumerImpl(
        grpcStub, grpcBlockingStub, subscription, streamName, pollTimeoutMs, maxPollRecords);
  }
}
