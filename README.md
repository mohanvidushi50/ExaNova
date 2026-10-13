# ExaNova — Fault-Tolerant Online Examination System

ExaNova is an online examination system built using **Java and MySQL**. It allows students to take exams online while demonstrating important Operating System (OS) and Database Management System (DBMS) concepts.

## Features

- Three roles: Student, Teacher, and Admin
- Create and manage exams and questions
- Supports Mcq,descriptive and coding questions
- Automatically check MCQ answers
- Allow teachers to check descriptive answers
- Timer and automatic answer saving
- Restore exam progress after a crash
- Track user activity and exam sessions

## Technology Stack

- **Backend:** Java
- **Database:** MySQL
- **Connectivity:** JDBC
- **Frontend:** HTML, CSS, JavaScript

## Requirements

Before running ExaNova, install:

- Java JDK
- MySQL Server
- MySQL JDBC Driver
- VS Code, IntelliJ IDEA, or Eclipse
- A web browser

## How to Run ExaNova

Step 1: Install the Requirements

Step 2: Open the Project
Download the project and open its folder in your IDE.

Step 3: Set Up the Database
1. Start MySQL Server.
2. Run the provided SQL setup file to create the database and tables.
3. Configure MySQL

Step 4: Run the Application

Step 5: Open ExaNova
Open the application in your browser if it runs as a web application, then log in using your account.

**Note:** Make sure MySQL is running before starting ExaNova.
## How It Works

1. Students log in and attempt exams.
2. Their answers and exam progress are saved in MySQL.
3. Teachers create exams and check descriptive answers.
4. The system generates results.
5. If the application crashes, recovery logs help restore saved progress and maintain consistent data.

## User Roles

| Role | Responsibilities |
|---|---|
| Student | Take exams, save answers, and view results |
| Teacher | Create exams, manage questions, and check answers |
| Admin | Monitor users, exam sessions, and activity logs |

## Operating System Concepts

- **Multithreading:** Handles multiple student sessions.
- **Synchronization:** Prevents conflicts when tasks run together.
- **Session Management:** Tracks the state of each exam.
- **Scheduling:** Manages concurrent tasks.
- **Write-Ahead Logging (WAL):** Records changes for recovery.
- **Crash Recovery:** Restores data after an unexpected failure.

## DBMS Concepts

- **Transactions:** Uses COMMIT and ROLLBACK to manage changes.
- **Concurrency Control:** Manages simultaneous database operations.
- **Data Integrity:** Keeps related data correct and consistent.
- **JDBC:** Connects Java with MySQL.
- **Checkpoints and Recovery:** Help restore data after a crash.


Browser
   |
Java Backend
   |
MySQL Database
   |
Exam Queue
   |
Worker Threads
   |
Save Answers
   |
Crash Recovery
