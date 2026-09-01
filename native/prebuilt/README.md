# Prebuilt armhf binaries (not tracked by git)

Built from RB16.04 + ../alljoyn-RB16.04-pi.patch in the container described in
../README.md. Verified on a Pi Zero 2 W (Raspbian 13 trixie, kernel 6.18.34,
glibc 2.41): five AllPlay speakers discovered, connect() ~1.5s.

  liballjoyn_java.so     the JNI library Tchaikovsky needs on java.library.path
  alljoyn-daemon         standalone router; discovers faster than the bundled one
  liballjoyn.so          shared lib the daemon links against
  alljoyn-daemon.conf    working daemon config
  PiTest2.java           discovery smoke test

## Deploy (bundled router, no daemon)

  scp liballjoyn_java.so <user>@<pi>:~/
  ssh <user>@<pi> 'java -Dorg.alljoyn.bus.address=null: \
      -Djava.library.path=$HOME -cp tchaikovsky.jar:. YourApp'

-Dorg.alljoyn.bus.address=null: is REQUIRED, otherwise connect() defaults to
unix:abstract=alljoyn and waits for a daemon that is not running.

## Deploy (standalone daemon, faster discovery)

  scp liballjoyn_java.so liballjoyn.so alljoyn-daemon alljoyn-daemon.conf <user>@<pi>:~/
  ssh <user>@<pi> 'chmod +x ~/alljoyn-daemon
      LD_LIBRARY_PATH=$HOME ~/alljoyn-daemon --config-file=$HOME/alljoyn-daemon.conf &
      java -Djava.library.path=$HOME -cp tchaikovsky.jar:. YourApp'
