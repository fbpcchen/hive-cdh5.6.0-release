# hive-cdh5.6.0-release
hive-cdh5.6.0-release对应的Apache-Hive的release-1.1.0

##编译记录
编译所需基础maven与java环境如下:   
Apache Maven 3.6.1  
Java version: 1.8.0_201  

进入项目根目录，参考官方给出的编译m命令https://dwz.cn/NNiY7aCz，由于cdh5.6.0使用的是hadoop-2.6.0，需指定 -Phadoop-2:  

`mvn clean package  -Phadoop-2 -Pdist -DskipTests`

可能遇到的编译错误问题包括:
```
[ERROR] Failed to execute goal org.datanucleus:datanucleus-maven-plugin:3.3.0-release:enhance (default) 
on project hive-metastore: Error executing DataNucleus tool org.datanucleus.enhancer.DataNucleusEnhancer:
InvocationTargetException: Plugin (Bundle) "org.datanucleus" is already registered. Ensure you dont have 
multiple JAR versions of the same plugin in the classpath. 
The URL "file:/var/root/.m2/repository/org/datanucleus/datanucleus-core/3.2.10/datanucleus-core-3.2.10.jar" 
is already registered, and you are trying to register an identical plugin located at URL "file:/private/var
/root/.m2/repository/org/datanucleus/datanucleus-core/3.2.10/datanucleus-core-3.2.10.jar." -> [Help 1]
```
从日志可以看出是在编译Hive Metastore时，使用Datanucleus对`*.jdo`文件进行enhance的时候报错，首先从报错信息来看怀疑是有
重复的jar包导致的问题，经查classpath中并没有重复的该jar包，然后单独在metastore中使用datanucleus-maven-plugin进行
enhance(出错前Model中已经产生class，只不过运行到enhance的时候出错所以class并没有被增强),发现能够生成enhanced class即
扩展了Persistence接口，所以怀疑是在编译过程中有重复加载该类的情况出现，查看一下plugin的配置,来看看hive-metastore中pom.xml
关于该plugin的配置:
```xml
<plugin>
    <groupId>org.datanucleus</groupId>
    <artifactId>datanucleus-maven-plugin</artifactId>
    <configuration>
        <api>JDO</api>
        <verbose>true</verbose>
        <metadataIncludes>**/*.jdo</metadataIncludes>
        <fork>false</fork>
    </configuration>
    <executions>
        <execution>
            <phase>process-classes</phase>
            <goals>
                <goal>enhance</goal>
            </goals>
        </execution>
     </executions>
</plugin>

```
怀疑是和`fork`的设置有关，查看了一下datanucleus enhance时候关于该选项的配置http://t.cn/AijqfkTA，可以看到这里使用了ant
中的org.apache.tools.ant.taskdefs.Java http://t.cn/AijqxxZv 来做为启动java命令的launcher:
```code
if (fork) {
    if (spawn) {
        spawn(commandLine.getCommandline());
        return 0;
    }
        return fork(commandLine.getCommandline()); // fork默认为true，另起一个jvm来运行，不会触发重复registered的bug
    }
try {
        run(commandLine); // 如果fork设置为false，ant会在同一个jvm中反射加载classpath中的类，会触发了重复registered的bug
        return 0;
    } catch (ExitException ex) {
        return ex.getStatus();
    }

```
所以修改 pom.xml 中的该选项， 将其设置为true或者去掉该选项(默认为true)，重新运行
`mvn clean package  -Phadoop-2 -Pdist -DskipTests`

最终显示如下信息代表编译成功:
```
[INFO] Reactor Summary for Hive 1.1.0-cdh5.6.0:
[INFO]
[INFO] Hive ............................................... SUCCESS [  2.645 s]
[INFO] Hive Shims Common .................................. SUCCESS [  4.291 s]
[INFO] Hive Shims 0.23 .................................... SUCCESS [  2.785 s]
[INFO] Hive Shims Scheduler ............................... SUCCESS [  1.919 s]
[INFO] Hive Shims ......................................... SUCCESS [  1.244 s]
[INFO] Hive Common ........................................ SUCCESS [  6.770 s]
[INFO] Hive Serde ......................................... SUCCESS [  4.814 s]
[INFO] Hive Metastore ..................................... SUCCESS [ 13.713 s]
[INFO] Hive Ant Utilities ................................. SUCCESS [  0.830 s]
[INFO] Spark Remote Client ................................ SUCCESS [  2.750 s]
[INFO] Hive Query Language ................................ SUCCESS [ 29.129 s]
[INFO] Hive Service ....................................... SUCCESS [  6.103 s]
[INFO] Hive Accumulo Handler .............................. SUCCESS [  4.652 s]
[INFO] Hive JDBC .......................................... SUCCESS [ 53.582 s]
[INFO] Hive Beeline ....................................... SUCCESS [  2.284 s]
[INFO] Hive CLI ........................................... SUCCESS [  2.619 s]
[INFO] Hive Contrib ....................................... SUCCESS [  2.492 s]
[INFO] Hive HBase Handler ................................. SUCCESS [  3.529 s]
[INFO] Hive HCatalog ...................................... SUCCESS [  0.761 s]
[INFO] Hive HCatalog Core ................................. SUCCESS [  4.650 s]
[INFO] Hive HCatalog Pig Adapter .......................... SUCCESS [  3.975 s]
[INFO] Hive HCatalog Server Extensions .................... SUCCESS [  4.335 s]
[INFO] Hive HCatalog Webhcat Java Client .................. SUCCESS [  3.122 s]
[INFO] Hive HCatalog Webhcat .............................. SUCCESS [  4.596 s]
[INFO] Hive HCatalog Streaming ............................ SUCCESS [  2.632 s]
[INFO] Hive HWI ........................................... SUCCESS [  2.549 s]
[INFO] Hive ODBC .......................................... SUCCESS [  1.438 s]
[INFO] Hive Shims Aggregator .............................. SUCCESS [  0.446 s]
[INFO] Hive TestUtils ..................................... SUCCESS [  1.014 s]
[INFO] Hive Packaging ..................................... SUCCESS [ 39.945 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  03:36 min
```
