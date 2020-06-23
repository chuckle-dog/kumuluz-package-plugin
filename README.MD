### Changes to Maven plugin:
__Goals__:
* __repackage__:
	* Added new config option: "_packagingType_"
	* Accepted values for _packageType_ are "uber" (default), "exploded", and "skimmed"
* __generate-dockerfile__:
	* New option
	* Uses the same "_packagingType_" config option as __repackage__
	* Generates a buildable Dockerfile and (if needed) .dockerignore for the project
	* Once the project is packaged with the matching _packagingType_, the Docker image can be built

* __generate-project__:
	* New option
	* Prompts the user for basic information (groupId, artifactId, projectVersion, and kumuluzEeVersion)
	* Generates a functional Hello-World microservice with all the necessary dependencies and with or without a basic 3-module (api-services-entities) structure
	
__SkimmedJAR__:

* Separated dependency runtime and application classes
* Application classes are packaged into a single JAR that is comparatively tiny
* Dependencies are kept in a separate /lib directory that the skimmed JAR references
* The packaged JAR can be run with the usual "java -jar [application_jar]" command as long as the required dependencies are present in _lib/_

__Dockerfile and Docker images__:

* Files are generated from templates depending on specified packaging type:
	* __uber__:
		* Image simply copies the uber JAR and exposes the application port
		* Building and rebuilding the image takes the same amount of time as the entire JAR must be copied each time
	* __skimmed__:
		* Image copies the skimmed jar and the _lib_ forlder with dependencies separately
		* Separation takes advantage of Docker layering
		* Initial build takes as much time as with the uber JAR
		* Subsequent rebuilds where only application code was changed are faster since Docker will reuse the layers with dependencies and only copy the tiny JAR again
		* With multi-module projects, the modules are treated as dependencies in _lib/_, but are copied separately in their own layers on top of the dependency layer (again allows for reuse of the dependency layer)

__Plugin example__:
```xml
<build>
   <plugins>
      <plugin>
         <groupId>com.kumuluz.ee</groupId>
         <artifactId>kumuluzee-maven-plugin</artifactId>
         <version>${kumuluz.version}</version>
         <executions>
            <execution>
	       <id>package</id>
	       <goals>
	          <goal>repackage</goal>
	          <goal>generate-dockerfile</goal>
	       </goals>
            </execution>
         </executions>
         <configuration>
            <packagingType>skimmed</packagingType>
         </configuration>
      </plugin>
   </plugins>
</build>
