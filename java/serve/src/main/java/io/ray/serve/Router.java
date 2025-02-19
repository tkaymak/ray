package io.ray.serve;

import com.google.common.collect.ImmutableMap;
import io.ray.api.BaseActorHandle;
import io.ray.api.ObjectRef;
import io.ray.runtime.metric.Count;
import io.ray.runtime.metric.Metrics;
import io.ray.serve.generated.RequestMetadata;
import io.ray.serve.poll.KeyListener;
import io.ray.serve.poll.KeyType;
import io.ray.serve.poll.LongPollClient;
import io.ray.serve.poll.LongPollNamespace;
import java.util.HashMap;
import java.util.Map;

/** Router process incoming queries: choose backend, and assign replica. */
public class Router {

  private ReplicaSet replicaSet;

  private Count numRouterRequests;

  private LongPollClient longPollClient;

  public Router(BaseActorHandle controllerHandle, String deploymentName) {
    this.replicaSet = new ReplicaSet(deploymentName);

    RayServeMetrics.execute(
        () ->
            this.numRouterRequests =
                Metrics.count()
                    .name(RayServeMetrics.SERVE_NUM_ROUTER_REQUESTS.getName())
                    .description(RayServeMetrics.SERVE_NUM_ROUTER_REQUESTS.getDescription())
                    .unit("")
                    .tags(ImmutableMap.of(RayServeMetrics.TAG_DEPLOYMENT, deploymentName))
                    .register());

    Map<KeyType, KeyListener> keyListeners = new HashMap<>();
    keyListeners.put(
        new KeyType(LongPollNamespace.BACKEND_CONFIGS, deploymentName),
        deploymentConfig -> replicaSet.setMaxConcurrentQueries(deploymentConfig)); // cross language
    keyListeners.put(
        new KeyType(LongPollNamespace.REPLICA_HANDLES, deploymentName),
        workerReplicas -> replicaSet.updateWorkerReplicas(workerReplicas)); // cross language
    this.longPollClient = new LongPollClient(controllerHandle, keyListeners);
    this.longPollClient.start();
  }

  /**
   * Assign a query and returns an object ref represent the result.
   *
   * @param requestMetadata the metadata of incoming queries.
   * @param requestArgs the request body of incoming queries.
   * @return ray.ObjectRef
   */
  public ObjectRef<Object> assignRequest(RequestMetadata requestMetadata, Object[] requestArgs) {
    RayServeMetrics.execute(() -> numRouterRequests.inc(1.0));
    return replicaSet.assignReplica(new Query(requestMetadata, requestArgs));
  }

  public ReplicaSet getReplicaSet() {
    return replicaSet;
  }
}
