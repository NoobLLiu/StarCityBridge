# 本地编译说明

StarCityBridge 依赖四个服务器插件作为 provided 依赖。云端 CI 会自动从 GitHub 仓库构建这些依赖；
本地编译需先把对应 jar 安装到本地 Maven 仓库：

```bash
# Residence（从 Zrips/Residence 源码构建）
git clone https://github.com/Zrips/Residence.git
cd Residence && mvn package -DskipTests
mvn install:install-file -Dfile=target/Residence6.0.2.2.jar -DgroupId=Residence -DartifactId=Residence -Dversion=6.0.2.2 -Dpackaging=jar

# AuthMe fork
mvn install:install-file -Dfile=AuthMe-5.7.0-FORK-Universal.jar -DgroupId=fr.xephi.authme -DartifactId=authme -Dversion=5.7.0-FORK -Dpackaging=jar

# MGTeam-JE（使用仓库 ci/build.sh 构建产物）
mvn install:install-file -Dfile=MGTeam-1.0.0.jar -DgroupId=cn.gmzc.mgteam -DartifactId=mgteam -Dversion=1.0.0 -Dpackaging=jar

# StockExchange（使用仓库 ci/build.sh 构建产物）
mvn install:install-file -Dfile=StockExchange-1.0.0-gmzc.jar -DgroupId=com.github.exchange -DartifactId=stockexchange -Dversion=1.0.0-gmzc -Dpackaging=jar
```

然后在本仓库执行：

```bash
mvn package
```

构建产物：`target/StarCityBridge.jar`
