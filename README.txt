----------------------------------------------------------------------------------------------------------------------------------------
how program works?
-local app-
the local app sends a message through the "app to manager sqs" informing the manager on the location of the input file the app.
then tries to read a message from the "manager to app sqs", if there is a message, checks if the file name is the same as the original input file and if so saves it, otherwise continues to look for its message. this check is used because the sqs can hold messages for different apps so only by comparison be can get the desired message dedicated to the correct app.
when the message is recieved, download the summaryfile using the file path provided in the message and delete it from the queue, then convert that file to an html file and save it localy.
if -t is given, send to the "app to manager sqs" a terminate message.

-manager-
global variables:
fileProcessingCount - maps input files to number of tasks given in the file to perform
urlMap - maps the input file path with its output summary file
executorService - a thread pool that will get tasks from the main thread and execute them. each task the pool will get will be to download the file using the file path given, then extract a list of task messages for each line read from the file, update the hashmaps accordingly, and then send all messages from the list to the "manager to worker sqs".
the main thread tries to read a message from the "app to manager sqs",
if it succseeds, checks if the message is a terminate message. if so, counts the number of running workers and sends them terminate messages using the "terminate sqs", waits for the sqs to be empty, and when its empty, terminates all worker instances. and then terminates iteself.
if the message is not a terminate message, makes a task for the thread pool from that message and submits it.
then the main thread tries to read a message from the "worker to manager sqs", if succseeds, understands from the message to which input file the message is related to and then apends the information from the message to the corresponding outputfile and updates the number of tasks left in the hashmap. when there are no more tasks left, sends the output file path as a message through the "manager to app sqs".
the manager continues to try to read the messages as explained above indefenetly.

-worker-
tries to read from the "manager to worker sqs", if succeeds, checks if the message is a terminate message.
if it is, the worker deletes the message from the queue and then ends its run.
if the message isnt a terminate message, the worker node downloads the file given in the message and then converts it according to the operation described in the message. then uploads the converted file to the s3 and sends its path back to the manager through the "worker to manager sqs".
then tries to read a new message again and so on.
----------------------------------------------------------------------------------------------------------------------------------------
how to run the program:
-connect to the aws
-copy credentials to the enviorment
-run command mvn install
-run localy "java -jar target/Ass_1-1.0-jar-with-dependencies.jar <input file name> <output file name> <max tasks per instance> <-t : optional for termination>"

----------------------------------------------------------------------------------------------------------------------------------------
what are the types of instances used:
-EC2-
-Manager node: we used t2.micro because we wanted an ec2 that will have a little more power to execute the managment of the tasks using a main thread for managment and a threadpool for reading the input files and extracting tasks from them.
-Worker node: we used t2.nano because the node is a linear working force performing one task at a time.
Both nodes are made using an ami that we made which runs on linux and has java installed with the pdfbox tool.
In addition, both nodes run a script which downloads the assignment jar from our s3 and then run the associated class.

-S3-
we used 2 buckets:
one for files uploaded from local apps for the manager to download -> input files
and a second for the converted pdf files and summary files -> output files

-SQS-
we used 5 sqs:
app to manager queue - for messages from the app to the manager, which are the locations of the input files on s3 and terminate message
manager to app queue - for messages from the manager to the app, which are the locations of the summary files made by the manager
manager to worker queue - for messages from the manager to the workers, which are messages holding the operation to perform and the location of the file
worker to manager queue - for messages from the workers to the manager, which are messages holding the operation that was performed and the location of the converted file
terminate queue - this is a queue between the manager and the workers, used for sending terminate messages for the workers

----------------------------------------------------------------------------------------------------------------------------------------
how much time it took the program to run and how did you choose the value of n (number of tasks per worker):
in order to achieve the most distributed program, the value of n should be small as possible so that more workers will work paralelly. however, more workers mean more ec2 and therefore more cost. in real practice, it is important to set in advance the ratio wanted between cost and time.
for this assignment we set n to be 1 so that the number of workers will be the maximum nodes possible (9).

----------------------------------------------------------------------------------------------------------------------------------------
security:
the cerdentials are stored localy and so they are not in the files uploaded to the s3.
therefore they are not accessable to other users.

----------------------------------------------------------------------------------------------------------------------------------------
scalability - will the program work properly with million local apps?
for maximum scalability, we are limited by the size of memory of the sqs (more apps mean more messages need to be stored in the sqs).
in addition, we will need to also make the manager work in a more concurent way, this will be done by making him initialize "mini manager" nodes that will perform as smaller manager node working on few  input messages so that the main manager will be more dedicated to distributing the messages to the mini managers rather than doing the tasks by itself.
memory size - in a big scale we will need to update the code so that the nodes will have acess to a data structure provided by the manager that will save all the buckets used. so that when a bucket has no memory left, the manager will make a new bucket so that all new uploaded files will be uploaded to the new one.

----------------------------------------------------------------------------------------------------------------------------------------
what happens if a node dies?
each message in the sqs is deleted only after the node finishes performing the task.
it is done by setting a visibility time variable for each message read from the sqs. the time set for the message is intended to let the worker node enough time to perform the operation so that no other node will read the same message resulting duplicate work. but, if a worker node dies in the middle of the job, after the visibility time ends, another worker node will be able to read the message and try to perform the task.
the manager node has a hashmap that stores each input file given from a local app with its number of tasks to perform, each time a worker nodes finishes its task, it deletes the message from the sqs so no other node will do the same task and then send the result to the manager. when the manager gets the message it adds the data to the corresponding summary file and also reduces by one the number of tasks left for that file. when a file gets to zero tasks left in the hashmap, it means that all tasks have been done for that file and that its time to send the summary file back to the apps sqs.

----------------------------------------------------------------------------------------------------------------------------------------
what if node stalls for a while?


----------------------------------------------------------------------------------------------------------------------------------------
threads in application:
Manager - distributing a message takes less time then downloading a file and extracting tasks from it. therefore we divided the worker node to a main thread that is in charge of distibuting the messages from the different queues and a threadpool (set to 2, but can be changed manually depending on the number of threads possible for that ec2) so that each thread in the pool will run on a message given, download it, extract tasks and send them to the workers
Worker - there is no need for concurency in the worker node due to it doing a single linear task.

----------------------------------------------------------------------------------------------------------------------------------------
explain the termination process:
if a local app gets -t in its initialized args, after the app will make the output html file given from the manager, the app will send a terminate message in the app to manger queue. then the manager will get that message from the sqs resulting sending in the dedicated terminate queue the same number of terminate messages as running workers, and then waiting for that queue to be emptied. each time a worker finishes a task, it checks the terminate queue before taking another. if there is a terminate message in the queue, the worker deletes that message from the queue and then imediatly finishes its run. those actions will result that when the terminate queue is empty, it means that all workers read a terminate message and stopped their work quietly, signaling the manager to terminate all worker nodes and then terminate itself.

----------------------------------------------------------------------------------------------------------------------------------------
what are the system limitations?
memory of the different ec2 nodes - a worker node will not be able to download a very large pdf which exceeds the size of its memory and therefore will not perform the task. the same can be said for large input files given to the manager.
memory of s3 - after the memory of the bucket is full with all the data uploaded to it, it will not be possible to upload more files to it.
memory of sqs - in case there are a lot of messages, it may result in making the sqs memory full so that new messages will not be added to the queue.
number of threads in the manager node - more threads will result in more input files being taken care of simutanicely.

----------------------------------------------------------------------------------------------------------------------------------------
are all workers work hard or some workers are slacking?
each worker takes one message and perform its code on it. which gives other worker nodes time to get a message while the other performs its task.
therefore only taskless workers try to get the messages. there still can be different numbers of tasks done by each worker but it is still distributed fairly.




*need to add the time took to run the program
*write all of this better
