# sjws_nettask
Simple Java Web Server

Basic multi-threaded (virtual threads) static file server. Requires Java 21+.

Build and test:

    mvn package

Run:

    java -XX:+UseG1GC -jar target/sjws_nettask-0.2-SNAPSHOT.jar
    java -XX:+UseG1GC -jar target/sjws_nettask-0.2-SNAPSHOT.jar <port>
    java -XX:+UseG1GC -jar target/sjws_nettask-0.2-SNAPSHOT.jar <port> <html folder path>

The `WEB_SERVER_PORT` and `WEB_SERVER_DIR` environment variables override the
arguments. Defaults: port 8080, folder `./HTML`.
