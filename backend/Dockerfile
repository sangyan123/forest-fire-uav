# =====================================================================
# backend 多阶段构建：
#   阶段1 maven:3.9-eclipse-temurin-21 构建（先 COPY pom.xml 下依赖，利用层缓存）
#   阶段2 eclipse-temurin:21-jre 运行
# =====================================================================

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# 先只拷贝 pom.xml 下载依赖（代码不变时命中缓存层）
COPY pom.xml .
RUN mvn -B dependency:go-offline

# 再拷贝源码打包
COPY src ./src
RUN mvn -B package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
