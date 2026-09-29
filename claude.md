A financial services platform needs to process user account upgrade requests by checking eligibility and notifying relevant stakeholders. Requests arrive from multiple sources: batches of existing users and real-time events     
from/web/mobile channels. Each request must pass a set of eligibility rules, and upon success or failure, generate appropriate notification (for the user, and optionally a parent). High throughput, reliability and scalable    
event processing are required. 

Requirements
1. Technology Stack (Required)
   Language: Java (preferably Java 17+)
   Framework: Spring Boot
   Event Processing: Kafka (mock with in memory queue or embedded Kafka for the test)
   Database: PostgreSQL (running instance not required - Use an in-memory DB or a stub/repository interface)
   Testing: JUnit/Mokito (write at least 2 business logic unit test cases)

2. Microservices Functionality
A. Ingest Request
   Provide two REST endpoints:
   1. POST /api/batch-upgrade: Accepts a list os user upgrade requests as JSON. Each request contains: userId, userName, age, balance, parentEmail(nullable).
   2. POST /api/realtime-upgrade: Accepts a single user upgrade request(same schema).
 Each request (in batch or real-time) is published as an event to a "upgrade-requests" Kafka topic for asynchronous processing.
      
    B: Eligibility Processing
    Build   a Kafka consumer/service that listens to "upgrade-requests" events.
    Implement an eligibility check with at least these rules:
        User must be between ages 18-23 (inclusive).
        Balance mush be >= $30
        userName cannot be empty.

    For failed eligibility, record failure reason(s).

    C. notification Logic
        For eligible users:
            simulate sending an email notification to the user (log to console or store in a simple in-memory list).
            If parentEmail is provided, also simulate notifying the parent.
        For ineligible users:
            Simulate sending an email to the user explaining failure reasons.
    D. Persistence
       For each processed request, store (in-memory map or stub repository is acceptable):
       1. userId
       2. processed status (ELIGIBLE/INELIGIBLE)
       3. reasons (if any)
       4. notificationSent(yes/no)
       5.timestamp
    E: Fetch Processed requests
     Provide REST endpoint GET /api/processed-upgrades that returns a list of all processed upgraded requests with all above fields.
3. Deliverables
   Running Spring Boot application
   REST API definitions for batch and real-time ingestion and processed requests retrieval.
   Kafka event simulation (use embedded or mock queue, no need for a true Kafka cluster).
   Eligibility Logic implemented with clear structure
   Notification simulation (log / email mock)
   Presentation layer  (in-memory or stub repository is sufficient)
   At least 2 unit tests with JUnit / Mockito for eligible logic.
   Instructions (in a README file or a comment at the top ) on how to build, run, and hit endpoints with sample payloads.
4. Constrains
   No frontend needed.
   Focus on clean code, microservice style, clear, service decomposition.
   Use dependency injection and reasonable error handling.
   Solution must demonstrate event-driven, asynchronous backend processing.
   
5. Evaluation Criteria
   Correctness and completeness of core flows (ingestion, eligibility, notification, storage, retrieval).
   Code organization, clarity, and modularity
   Usage of asynchronous / event-driven patterns (Kafka or simulation)
   Unit test quality and coverage.
   Clear written instructions and sample requests.

6. Code Conventions (enforced by ImplementationNamingConventionTest in the backend build)
   Interfaces are the ports between modules (ProcessedUpgradeRepository, EventPublisher, EmailSender, EmailChannel,
   NotificationLog, UpgradeRequestHandler, EligibilityRule). Other modules depend only on the interface.
   A class that implements one of the application's own interfaces:
      - is named <Name>Impl. The only implementation of an interface is named after it
        (EmailSender -> EmailSenderImpl, ProcessedUpgradeRepository -> ProcessedUpgradeRepositoryImpl);
        several implementations of one interface keep a descriptive name (AgeRangeRuleImpl, UserNameRuleImpl).
      - lives in an "impl" package of its module (persistence.impl, notification.impl, messaging.kafka.impl,
        processing.impl, eligibility.impl).
   Nothing else goes in an impl package, and nothing else is named *Impl.
   Classes implementing only framework interfaces (Spring, Kafka, servlet: CorsConfig, ProductionReadinessCheck,
   filters) keep framework-style names. Test doubles are exempt.
   Tests of an implementation mirror its package (e.g. persistence.impl.ProcessedUpgradeRepositoryImplTest).

 