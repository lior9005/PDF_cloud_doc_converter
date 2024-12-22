# Project README

## How the Program Works

### Local App
The local application interacts with the system as follows:
1. **Send Input File Location**: The app sends a message to the `app-to-manager` SQS, specifying the location of the input file on S3.
2. **Retrieve Processed File**: It continuously checks the `manager-to-app` SQS for a message containing the summary file's location.
   - If the file name matches the original input file, the app downloads the summary file, converts it to HTML, and saves it locally.
   - If no matching message is found, it continues polling the queue.
3. **Termination (Optional)**: If the `-t` flag is provided, the app sends a termination message to the `app-to-manager` SQS.

### Manager
The manager orchestrates tasks and resources using the following components:
- **Global Variables**:
  - `fileProcessingCount`: Maps input files to the number of tasks they contain.
  - `urlMap`: Maps input file paths to their corresponding output summary files.
  - `executorService`: A thread pool that:
    - Downloads input files from S3.
    - Extracts tasks from each file.
    - Updates hash maps and sends task messages to the `manager-to-worker SQS` queue.
- **Message Handling**:
  1. Reads messages from the `app-to-manager SQS` queue.
     - If a termination message is received, the manager:
       - Counts running workers and sends them termination messages via the `terminate SQS` queue.
       - Waits for the queue to empty before terminating worker instances and itself.
     - If not a termination message, it submits a task to the thread pool for processing.
  2. Reads messages from the `worker-to-manager SQS` queue.
     - Updates the corresponding summary file and decrements the task count in `fileProcessingCount`.
     - When all tasks for a file are complete, sends the summary file path to the `manager-to-app SQS` queue.
  3. Continuously loops to handle messages as described above.

### Worker
The worker processes tasks as follows:
1. Reads messages from the `manager-to-worker SQS` queue.
   - If the message is a termination command, the worker deletes it from the queue and shuts down.
   - If not, the worker downloads the specified file, processes it, and uploads the result to S3.
2. Sends the result's file path to the `worker-to-manager SQS` queue.
3. Repeats the process until terminated.

---

## How to Run the Program
1. **Set Up AWS**:
   - Connect to AWS.
   - Copy credentials to the environment.
2. **Build the Project**:
   - Run: `mvn install`
3. **Execute the Application**:
   - Run locally: `java -jar target/Ass_1-1.0-jar-with-dependencies.jar <input file name> <output file name> <max tasks per instance> [-t (optional)]`

---

## Types of Instances Used

### EC2
- **Manager Node**: Uses `t2.micro` for sufficient computational power to manage tasks using a main thread and a thread pool.
- **Worker Node**: Uses `t2.nano` for cost-effective execution of single linear tasks.

Both nodes are built from a custom Linux AMI with Java and PDFBox pre-installed. Each node runs a startup script to download the assignment JAR file from S3 and execute the relevant class.
Also, each node initialized with a specific IAM role called ("labRole") which grants the relevant permission to execute the task.

### S3
- **Input Bucket**: Stores files uploaded by local apps for the manager to process.
- **Output Bucket**: Stores processed files and summary files for the local app to retrieve.

### SQS
- **Queues**:
  - `app-to-manager`: Transfers input file locations and termination messages from the app to the manager.
  - `manager-to-app`: Sends summary file locations from the manager to the app.
  - `manager-to-worker`: Dispatches tasks from the manager to workers.
  - `worker-to-manager`: Returns processed file locations from workers to the manager.
  - `terminate`: Sends termination messages to workers.

---

## Performance and Scalability

### Runtime and Task Distribution
- **Tasks Per Worker (`n`)**:
  - Smaller `n` values distribute tasks across more workers, reducing runtime but increasing costs.
  - For this assignment, `n=1` was used, maximizing the number of active workers (up to 9).

### Scalability
- **Challenges**:
  - SQS message storage limits.
  - Manager’s single-threaded limitations.
  - S3 bucket memory limits.
- **Solutions**:
  - Scale the manager using "mini-manager" nodes to handle subsets of tasks.
  - Use dynamic bucket creation when S3 memory is exhausted.

---

##Security
- The credentials are stored locally and are not included in files uploaded to S3.
- This ensures they remain inaccessible to unauthorized users.

---

## Fault Tolerance
- Messages are deleted from SQS only after task completion.
- If a worker node fails, the message becomes visible again after the visibility timeout, allowing another worker to retry.
- The manager tracks task progress using `fileProcessingCount` to ensure all tasks are completed before sending the summary file.

---

## Thread Management

### Manager
- A main thread handles message distribution.
- A thread pool (default size: 2) processes input files and extracts tasks.

### Worker
- Single-threaded, performing one task at a time.

---

## Termination Process
1. The local app sends a termination message via the `app-to-manager` SQS.
2. The manager:
   - Sends termination messages to all active workers via the `terminate` SQS.
   - Waits for the queue to empty, ensuring all workers have stopped.
3. Workers check the `terminate` SQS after each task. Upon receiving a termination message, they shut down gracefully.
4. Once all workers have terminated, the manager shuts down itself.

---

## System Limitations
- **EC2 Memory**: Workers and the manager cannot handle files larger than their memory capacity.
- **S3 Storage**: Bucket memory limits may prevent additional uploads.
- **SQS Limits**: Large numbers of messages may exceed SQS capacity.
- **Manager Threads**: Limited thread count affects parallel task handling.

---

## Worker Load Distribution
Workers process tasks fairly by pulling messages from the queue as they become available. Task distribution depends on queue availability and worker speed, ensuring efficient resource use.



----------------------------------------------------------------------------------------------------------------------------------------
how much time it took the program to run - left to do
