docker run --rm \
  --user "$(id -u):$(id -g)" \
  -v "$HOME/.m2":/var/maven/.m2 \
  -e MAVEN_CONFIG=/var/maven/.m2 \
  -v "$(pwd)":/app \
  -w /app \
  maven:3.9-eclipse-temurin-21 \
  mvn -Duser.home=/var/maven clean package

# Copiar el JAR generado a la carpeta de plugins del servidor
mkdir -p server/plugins
cp dist/target/dist-1.3.jar server/plugins/Permadeath.jar
echo "Compilado y copiado a server/plugins/Permadeath.jar con éxito, iniciando servidor..."
cd server
./start.sh


