## akkevad - AKKa EVent ADmin

OSGi Event Admin implementation using Akka.

#### The event topics hierarchy of actors

This is how the topics are translated into actors where the string in parentheses are the topic that the actor will handle. Wildcards are also supported, for instance the actor **that** will also handle events for EventHandlers registered with the topic **my/topic/that/***

Of course the wildcard topic is supported as well, the parent event admin actor will handle it (hopefully). But who are using that, anyway? :)

```
Event admin actor (*)
└── my (my)
    ├─ topic (my/topic)
    |  └── that (my/topic/that)
    │      └── is (my/topic/that/is)
    │          └── pretty (my/topic/that/is/pretty)
    │              └──long (my/topic/that/is/pretty/long)
    │
    └─ other (my/other)
       └─ topic (my/other/topic)
```
