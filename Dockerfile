# Minimalna Java 17 slika
FROM openjdk:17-jdk-slim

# Radni folder
WORKDIR /app

# Kopiraj ceo projekat
COPY . /app

# Kompajliraj sve .java fajlove iz src/ koristeći biblioteke iz lib/
RUN find src -name "*.java" > sources.txt \
 && javac -cp "lib/*" @sources.txt

# Railway dodeljuje PORT promenljivu → koristi je
EXPOSE 8080

# Start servera (dodaj flag zbog SQLite warning-a)
CMD ["sh", "-c", "java --enable-native-access=ALL-UNNAMED -cp 'lib/*:src' WebServer"]
