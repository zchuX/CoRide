{
  "apiEndpoints": {
    "CreateTrip": {
      "description": "Creates a new trip. tripArn is server-generated. Group arns are server-generated; do not send arn/groupArn on any group. Driver must be the authenticated caller (use sub from auth/me); driver name resolved from JWT claim. If driver is omitted and groups is empty, the caller is set as driver automatically.",
      "request": {
        "method": "POST",
        "path": "/api/trips",
        "headers": {
          "Authorization": "Bearer <idToken>",
          "x-api-key": "<apiKey>"
        },
        "body": {
          "startTime": {
            "type": "Long",
            "required": true,
            "description": "Trip start time as Unix ms timestamp."
          },
          "start": {
            "type": "Option[String]",
            "description": "Top-level start location name. Falls back to first group's start when omitted."
          },
          "destination": {
            "type": "Option[String]",
            "description": "Top-level destination name. Falls back to first group's destination when omitted."
          },
          "driver": {
            "type": "Option[String]",
            "description": "Caller's userArn (verified.sub). Must equal authenticated user. Omit to create a passenger-only trip."
          },
          "car": {
            "type": "Option[Car]",
            "description": "Car object: plateNumber, color, model (all optional strings)."
          },
          "notes": {
            "type": "Option[String]",
            "description": "Free-text notes for the trip."
          },
          "groups": {
            "type": "List[GroupInput]",
            "description": "List of groups. Do NOT send arn/groupArn – server generates it. Each group: groupName (String, required), start (String), destination (String), pickupTime (Long ms), users (List[GroupUser], optional). GroupUser: userArn (String, required – same as Users table key, e.g. Cognito sub), name (String), imageUrl (optional), accept (optional, default false). Each listed user must exist; creation fails with 400 if userArn is missing or not found."
          }
        }
      },
      "response": {
        "200": {
          "description": "The created trip.",
          "body": {
            "tripArn": "String",
            "startTime": "Long",
            "status": "String (Upcoming)",
            "driver": "Option[String]",
            "driverName": "Option[String]",
            "driverConfirmed": "Boolean",
            "car": "Option[Car]",
            "notes": "Option[String]",
            "locations": "List[Location]",
            "usergroups": "List[UserGroupSummary]",
            "version": "Int"
          }
        },
        "400": "Missing startTime or body parse error. If a group user has no userArn or userArn is not found: message 'Each group user must have userArn' or 'User not found' with invalidUserArns array.",
        "401": "Missing or invalid Bearer token.",
        "403": "Driver field does not match authenticated user."
      }
    },
    "GetTripById": {
      "description": "Gets a trip by its ARN.",
      "request": {
        "method": "GET",
        "path": "/api/trips/{tripArn}",
        "headers": {
          "Authorization": "Bearer <idToken>",
          "x-api-key": "<apiKey>"
        }
      },
      "response": {
        "200": {
          "description": "Trip metadata and the calling user's trip status.",
          "body": {
            "trip": {
              "type": "TripMetadata",
              "description": "Full trip: tripArn, startTime, completionTime, status, currentStop, driver, driverName, driverPhotoUrl, driverConfirmed, car, notes, locations, usergroups, version."
            },
            "status": {
              "type": "Object",
              "description": "{ userTripStatus: String | null } – status of the calling user's UserTrip record."
            }
          }
        },
        "401": "Missing or invalid Bearer token.",
        "404": "Trip not found."
      }
    },
    "GetUserTrips": {
      "description": "Gets all trips for the authenticated user.",
      "request": {
        "method": "GET",
        "path": "/api/trips",
        "headers": {
          "Authorization": "Bearer <idToken>",
          "x-api-key": "<apiKey>"
        },
        "queryParams": {
          "completed": {
            "type": "Boolean",
            "required": true,
            "description": "true = completed trips (userStatusKey ends with -completed). false = uncompleted trips."
          }
        }
      },
      "response": {
        "200": {
          "description": "List of the user's trips.",
          "body": {
            "trips": {
              "type": "List[UserTripListItem]",
              "description": "Each item: tripArn, startTime, status, start, destination, isDriver, driverConfirmed, userTripArn, userTripStatus, groupArn."
            }
          }
        },
        "400": "Missing or invalid completed param.",
        "401": "Missing or invalid Bearer token."
      }
    },
    "ListTripUsers": {
      "description": "Gets all UserTrip records for a trip.",
      "request": {
        "method": "GET",
        "path": "/api/trips/{tripArn}/users",
        "headers": {
          "x-api-key": "<apiKey>"
        }
      },
      "response": {
        "200": {
          "description": "All UserTrip rows for the trip.",
          "body": {
            "users": {
              "type": "List[UserTrip]",
              "description": "Each UserTrip: arn, tripArn, userStatusKey, tripDateTime, tripStatus, start, destination, departureDateTime, isDriver, driverConfirmed, version."
            }
          }
        }
      }
    },
    "UpdateTripMetadata": {
      "description": "Updates trip fields: location order and/or startTime only. Only the driver or a group member may update. Location order must be a permutation of existing locations; arrived locations cannot be moved to a later position. Notes are not updatable via this API.",
      "request": {
        "method": "PUT",
        "path": "/api/trips/{tripArn}",
        "headers": {
          "Authorization": "Bearer <idToken>",
          "x-api-key": "<apiKey>",
          "X-Expected-Version": "<Int> (optional optimistic concurrency; defaults to current version)"
        },
        "body": {
          "startTime": {
            "type": "Option[Long]",
            "description": "Updated start time (Unix ms)."
          },
          "locations": {
            "type": "Option[List[String]]",
            "description": "Ordered list of location names (permutation of existing). Cannot move an already-arrived location later."
          },
          "car": {
            "type": "Option[Car]",
            "description": "Updated car info: plateNumber, color, model (all optional strings). Omit to leave car unchanged; send null or {} to clear."
          }
        }
      },
      "response": {
        "200": {
          "description": "Updated TripMetadata (includes car, driverName, driverPhotoUrl, notes, version).",
          "body": "TripMetadata"
        },
        "400": "Validation error (invalid location reorder, etc.).",
        "401": "Missing or invalid Bearer token.",
        "403": "Caller is not driver or group member.",
        "404": "Trip not found.",
        "409": "Version conflict."
      }
    },
    "StartTrip": {
      "description": "Starts an Upcoming trip. Only the driver may call this. Transitions trip and all UserTrips to InProgress in a single transaction. If the first location has no pickup groups, it is marked arrived automatically.",
      "request": {
        "method": "POST",
        "path": "/api/trips/{tripArn}/start",
        "headers": {
          "Authorization": "Bearer <idToken>",
          "x-api-key": "<apiKey>",
          "X-Expected-Version": "<Int> (optional)"
        }
      },
      "response": {
        "200": {
          "description": "Updated TripMetadata with status InProgress.",
          "body": "TripMetadata"
        },
        "400": "Trip already started.",
        "401": "Missing or invalid Bearer token.",
        "403": "Caller is not the driver.",
        "404": "Trip not found.",
        "409": "Version conflict."
      }
    },
    "ArriveLocation": {
      "description": "Marks a location as arrived. Caller must be the driver or a member of a group whose start or destination matches the location. If all locations are now arrived, the trip and all UserTrips are completed in a single transaction (tripStatus = Completed, userStatusKey = {userId}-completed).",
      "request": {
        "method": "POST",
        "path": "/api/trips/{tripArn}/locations/{locationName}/arrival",
        "headers": {
          "Authorization": "Bearer <idToken>",
          "x-api-key": "<apiKey>",
          "X-Expected-Version": "<Int> (optional)"
        },
        "body": {
          "tripArn": {
            "type": "String",
            "required": true
          },
          "locationName": {
            "type": "String",
            "required": true
          },
          "arrived": {
            "type": "Boolean",
            "required": true,
            "description": "Must be true (only arriving is supported)."
          }
        }
      },
      "response": {
        "200": {
          "description": "Updated TripMetadata.",
          "body": "TripMetadata"
        },
        "400": "arrived must be true.",
        "401": "Missing or invalid Bearer token.",
        "403": "Too early (now < startTime) or caller not authorised for this location.",
        "404": "Trip not found.",
        "409": "Version conflict."
      }
    },
    "LeaveTrip": {
      "description": "Removes the authenticated user from their group in a trip. If the group or trip becomes empty after removal, it is deleted transactionally.",
      "request": {
        "method": "POST",
        "path": "/api/trips/{tripArn}/leave",
        "headers": {
          "Authorization": "Bearer <idToken>",
          "x-api-key": "<apiKey>",
          "X-Expected-Version": "<Int> (optional)"
        }
      },
      "response": {
        "200": {
          "description": "Confirmation.",
          "body": {
            "message": "Successfully left trip"
          }
        },
        "401": "Missing or invalid Bearer token.",
        "404": "Trip not found or caller not in any group.",
        "409": "Version conflict."
      }
    },
    "BecomeDriver": {
      "description": "Sets the authenticated user as the driver for a trip. Resolves driver name and photo URL from UserDAO and stores them on TripMetadata.",
      "request": {
        "method": "POST",
        "path": "/api/trips/{tripArn}/driver",
        "headers": {
          "Authorization": "Bearer <idToken>",
          "x-api-key": "<apiKey>",
          "X-Expected-Version": "<Int> (optional)"
        }
      },
      "response": {
        "200": {
          "description": "Updated TripMetadata with driver set.",
          "body": "TripMetadata"
        },
        "401": "Missing or invalid Bearer token.",
        "404": "Trip not found.",
        "409": "Version conflict."
      }
    },
    "CreateUserGroup": {
      "description": "Creates a new user group on an existing trip. groupArn is server-generated; do not send it. The caller's accept flag is forced to true if they appear in users.",
      "request": {
        "method": "POST",
        "path": "/api/user-groups",
        "headers": {
          "Authorization": "Bearer <idToken>",
          "x-api-key": "<apiKey>",
          "X-Expected-Version": "<Int> (optional, trip version)"
        },
        "body": {
          "tripArn": {
            "type": "String",
            "required": true,
            "description": "ARN of the trip."
          },
          "groupName": {
            "type": "String",
            "required": true
          },
          "start": {
            "type": "String",
            "required": true
          },
          "destination": {
            "type": "String",
            "required": true
          },
          "pickupTime": {
            "type": "Long",
            "required": true,
            "description": "Unix ms timestamp."
          },
          "users": {
            "type": "List[GroupUser]",
            "description": "GroupUser: userId, name, imageUrl (optional), accept (optional, default false)."
          }
        }
      },
      "response": {
        "200": {
          "description": "The created user group. groupArn is server-generated.",
          "body": {
            "arn": "String (server-generated group:uuid)",
            "tripArn": "String",
            "groupName": "String",
            "start": "String",
            "destination": "String",
            "pickupTime": "Long",
            "users": "List[GroupUser]",
            "version": "Int"
          }
        },
        "401": "Missing or invalid Bearer token.",
        "404": "Trip not found.",
        "409": "Version conflict or duplicate user."
      }
    },
    "GetUserGroup": {
      "description": "Gets a user group by its ARN. The groupArn in the path is URL-decoded server-side (group%3Auuid → group:uuid).",
      "request": {
        "method": "GET",
        "path": "/api/user-groups/{groupArn}",
        "headers": {
          "Authorization": "Bearer <idToken>",
          "x-api-key": "<apiKey>"
        }
      },
      "response": {
        "200": {
          "description": "UserGroupRecord.",
          "body": "UserGroupRecord"
        },
        "401": "Missing or invalid Bearer token.",
        "404": "Group not found."
      }
    },
    "UpdateUserGroup": {
      "description": "Updates a user group. Caller must be a member. Adding/removing users updates UserTrip records transactionally. Also updates TripMetadata locations and usergroups summary.",
      "request": {
        "method": "PUT",
        "path": "/api/user-groups/{groupArn}",
        "headers": {
          "Authorization": "Bearer <idToken>",
          "x-api-key": "<apiKey>",
          "X-Expected-Version": "<Int> (optional, group version)",
          "X-Expected-Trip-Version": "<Int> (optional, trip version)"
        },
        "body": {
          "groupName": {
            "type": "Option[String]"
          },
          "start": {
            "type": "Option[String]"
          },
          "destination": {
            "type": "Option[String]"
          },
          "pickupTime": {
            "type": "Option[Long]"
          },
          "users": {
            "type": "Option[List[GroupUser]]",
            "description": "Full replacement list. Added users get a UserTrip; removed users have their UserTrip deleted."
          }
        }
      },
      "response": {
        "200": {
          "description": "Updated UserGroupRecord.",
          "body": "UserGroupRecord"
        },
        "400": "Validation error (e.g., driver already in group).",
        "401": "Missing or invalid Bearer token.",
        "403": "Caller is not a group member.",
        "404": "Group not found.",
        "409": "Version conflict."
      }
    },
    "AcceptInvitation": {
      "description": "Accepts a pending invitation for the authenticated user in a group. Updates user accept=true and moves UserTrip from Invitation to the current trip status in one transaction.",
      "request": {
        "method": "POST",
        "path": "/api/user-groups/{groupArn}/accept",
        "headers": {
          "Authorization": "Bearer <idToken>",
          "x-api-key": "<apiKey>",
          "X-Expected-Version": "<Int> (optional, group version)"
        }
      },
      "response": {
        "200": {
          "description": "Updated UserGroupRecord.",
          "body": "UserGroupRecord"
        },
        "400": "User already accepted or not in Invitation status.",
        "401": "Missing or invalid Bearer token.",
        "404": "Group or user trip not found.",
        "409": "Version conflict."
      }
    },
    "JoinUserGroup": {
      "description": "Joins a group. Caller must not already be in the group. Appends caller to the group's users list with accept=true and creates a UserTrip for the joiner. Uses updateUserGroup under the hood.",
      "request": {
        "method": "POST",
        "path": "/api/user-groups/{groupArn}/join",
        "headers": {
          "Authorization": "Bearer <idToken>",
          "x-api-key": "<apiKey>",
          "X-Expected-Version": "<Int> (optional)"
        }
      },
      "response": {
        "200": {
          "description": "Updated UserGroupRecord.",
          "body": "UserGroupRecord"
        },
        "400": "Caller already in group.",
        "401": "Missing or invalid Bearer token.",
        "404": "Group or trip not found.",
        "409": "Version conflict."
      }
    }
  },
  "models": {
    "TripMetadata": {
      "tripArn": "String",
      "startTime": "Long (ms)",
      "completionTime": "Option[Long]",
      "status": "String (Upcoming | InProgress | Completed)",
      "currentStop": "Option[String]",
      "driver": "Option[String] (userArn)",
      "driverName": "Option[String]",
      "driverPhotoUrl": "Option[String]",
      "driverConfirmed": "Option[Boolean]",
      "car": "Option[Car]",
      "notes": "Option[String]",
      "locations": "List[Location]",
      "usergroups": "Option[List[UserGroupSummary]]",
      "version": "Int"
    },
    "Location": {
      "locationName": "String",
      "plannedTime": "Long (Unix ms). Stored only in TripMetadata; set only during transactional writes (trip creation, user group add/update/remove).",
      "pickupGroups": "List[String] (group names)",
      "dropOffGroups": "List[String] (group names)",
      "arrived": "Boolean",
      "arrivedTime": "Option[Long]"
    },
    "Car": {
      "plateNumber": "Option[String]",
      "color": "Option[String]",
      "model": "Option[String]"
    },
    "UserGroupSummary": {
      "groupId": "String (groupArn)",
      "groupName": "String",
      "groupSize": "Int",
      "imageUrl": "Option[String]"
    },
    "UserGroupRecord": {
      "arn": "String (group:uuid, server-generated)",
      "tripArn": "String",
      "groupName": "String",
      "start": "String",
      "destination": "String",
      "pickupTime": "Long (ms)",
      "users": "List[GroupUser]",
      "version": "Int"
    },
    "GroupUser": {
      "userArn": "String (required on create – same as Users table key, e.g. Cognito sub). Must reference an existing user.",
      "name": "String",
      "imageUrl": "Option[String]",
      "accept": "Boolean"
    },
    "UserTrip": {
      "arn": "String (tripArn:userId)",
      "tripArn": "String",
      "userStatusKey": "String ({userId}-uncompleted | {userId}-completed) – GSI key",
      "tripDateTime": "Long (ms)",
      "tripStatus": "String (Upcoming | Invitation | InProgress | Completed)",
      "start": "String",
      "destination": "String",
      "departureDateTime": "Long (ms)",
      "isDriver": "Boolean",
      "driverConfirmed": "Boolean",
      "version": "Int"
    }
  }
}