## Resource event history

For each managed resource, keel keeps a history of *resource events*.
Users can view this history in the clusters view of Deck by hovering the mouse of over the Managed icon and clicking the "History button".

### How events get recorded

The [ResourceHistoryListener] listens for Spring events subclassed from [ResourceEvent], and writes the events to the databaase.
For example, the [ResourceActuator] publishes a [ResourceActuationLaunched] event after the actuator starts the process of creating or updating a resource.


[ResourceHistoryListener]: ../keel-core/src/main/kotlin/com/netflix/spinnaker/keel/events/ResourceHistoryListener.kt
[ResourceEvent]: ../keel-core/src/main/kotlin/com/netflix/spinnaker/keel/events/ResourceEvent.kt
[ResourceActuator]: ../keel-core/src/main/kotlin/com/netflix/spinnaker/keel/actuation/ResourceActuator.kt
[ResourceActuationLaunched]: ../keel-core/src/main/kotlin/com/netflix/spinnaker/keel/events/ResourceEvent.kt
