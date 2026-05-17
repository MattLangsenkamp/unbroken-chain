# Purpose

UBC is a work in progress. The following is the intent for the system

Unbroken chain (UBC) is a code retrieval system that connects to a git provider and can be queried by web gui, CLI or MCP servers. It should reduce token and context usage by finding the "right code" faster. One UBC installation is meant to be used by many people in an organization. Below is an example usage.

A system admin installs UBC in a k8s cluster for company A. company A links the github org and all its repos to the UBC installation. UBC indexes new code as commits are pushed to the linked repos. UBC authenticates with company A's identity provider (Okta, Keycloak, etc). Users authenticate and use UBC in the web gui and through the MCP with agents to write code more efficiently. 

