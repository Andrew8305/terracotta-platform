/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.lease;

import org.terracotta.connection.Connection;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

class CleaningLeaseMaintainer implements LeaseMaintainer {
  private final LeaseMaintainer delegate;
  private final Connection connection;
  private final List<Thread> threads;


  CleaningLeaseMaintainer(LeaseMaintainer delegate, Connection connection, Thread... threads) {
    this(delegate, connection, Arrays.asList(threads));
  }

  private CleaningLeaseMaintainer(LeaseMaintainer delegate, Connection connection, List<Thread> threads) {
    this.delegate = delegate;
    this.connection = connection;
    this.threads = threads;
  }

  @Override
  public Lease getCurrentLease() {
    return delegate.getCurrentLease();
  }

  @Override
  public void waitForLease() throws InterruptedException {
    delegate.waitForLease();
  }

  @Override
  public boolean waitForLease(long timeout, TimeUnit timeUnit) throws InterruptedException {
    return delegate.waitForLease(timeout, timeUnit);
  }

  @Override
  public void close() throws IOException {
    for (Thread thread : threads) {
      thread.interrupt();
    }

    // We could join on these threads, but that's not a great idea because
    // LeaseExpiryConnectionKillingThread may be calling connection.close() leading to this method being called
    // and each is then waiting for the other to complete - deadlock.

    delegate.close();
  }

  @Override
  public void destroy() throws IOException {
    for (Thread thread : threads) {
      thread.interrupt();
    }

    connection.close();
  }
}
