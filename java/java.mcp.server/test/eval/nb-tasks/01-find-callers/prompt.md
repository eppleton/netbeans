<!--

    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.

-->

The current directory is the `java` folder of the Apache NetBeans source tree: about 160 NetBeans module projects (Ant based, each with src/ and test/ folders). Find every place in this folder that calls the method `getClasspathInfoFor` of the class `org.netbeans.modules.refactoring.java.api.JavaRefactoringUtils`. Other methods of the same name in other classes do not count.

Write the result to a file `answer.txt` in the current directory, one call site per line as `path:line`, with the path relative to the current directory, e.g. `refactoring.java/src/org/netbeans/Foo.java:42`. Do not change any other file.
