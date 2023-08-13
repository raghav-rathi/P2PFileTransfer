# CNT4007-Project
## Group Members
Nathan Hilton, Adrian Salazar, and Logan Rotenberger

# Link To Demo Video
https://uflorida-my.sharepoint.com/:v:/g/personal/nathanhilton_ufl_edu/EbA7z5ULIm9OvCbnw3MH9G8B1IZ-aMPflhZ6F0PqjEr0fQ?e=II0Cm2

## Initial Set Up
1. Connect to UF's network though the VPN (info found here: https://it.ufl.edu/ict/documentation/network-infrastructure/vpn/)
2. SSH into one of the machines. You will need to use your gatorlink password to authenicate. An example of a machine to use would be lin114-00.cise.ufl.edu and the command to connect would be:
```
ssh lin114-00.cise.ufl.edu
```
3. Clone the github repo. You will need to create a personal access token to be able to do this (link: https://github.com/settings/tokens). First clone the repo, then it will prompt you for you username followed by the password (which is your personal access token).
4. Make sure all the folders that will be written to in the code exist (ex: 1001)

## How To Run On The CISE Servers
1. Connect to UF's network though the VPN
2. SSH into the machines found in peerInfo.cfg in different terminal windows. If the first machine in the file is lin114-00.cise.ufl.edu, run the command: 
```
ssh lin114-00.cise.ufl.edu
```
3. In the first machine compile peerProcess.java and then run with the first peer id value. For the first machine you would run:
```
javac peerProcess.java
java peerProcess 1001
```
4. Repeat this process for all the other machines, in the order in peerInfo.cfg
