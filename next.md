see to explain structure
- https://docs.corda.net/tutorial-cordapp.html

- ./gradlew deployNodes
- kotlin-source/build/nodes/runnodes (using terminal)
    - starts three nodes notary,PartyA and PartyB
- start webServers PartyA and Party B Servers via Intellij Debug mode          

- WebServer Starting Point

Party A - http://localhost:10020
Party B - http://localhost:10021

PartyA / agreeJob with Party B
--Add validation , cannot create contract with self

Contractor -> "O=PartyB,L=New York,C=US"
Notary -> "O=Notary,L=London,C=GB"
Milestone -> milestone1,milestone2
Milestone Quantities -> 100,200
Milestone Currency -> GBP

Result - 
New job created with ID 6a1f591a-3ee6-4460-8232-64eeb6710dde

PartyB / Start Milestone

Linear ID -> 6a1f591a-3ee6-4460-8232-64eeb6710dde
Milestone Index -> 0

Result:-
Milestone # 0 started for Job ID 6a1f591a-3ee6-4460-8232-64eeb6710dde.


PartyB / Finish Milestone

Milestone # 0 finished for Job ID 6a1f591a-3ee6-4460-8232-64eeb6710dde.

create  job as party a -> create <job id> ,  comma separated list for milestone
start milestone as party b > input <job id> and zero based for milestone, first  is zero 
finish milestone as party b > <job id> <milestone index>  
accept milestone as party a > <job id> <milestone index> 