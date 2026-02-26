{
  "apiEndpoints": {
    "CreateTrip": {
      "description": "Creates a new trip.",
      "request": {
        "method": "POST",
        "path": "/trips",
        "body": {
          "startTime": {"type": "Long", "description": "The start time of the trip as a Unix timestamp."},
          "start": {"type": "Option[String]", "description": "An optional start location for the trip."},
          "destination": {"type": "Option[String]", "description": "An optional destination for the trip."},
          "driver": {"type": "Option[String]", "description": "An optional driver ID for the trip."},
          "car": {"type": "Option[Car]", "description": "An optional car object for the trip."},
          "notes": {"type": "Option[String]", "description": "Optional notes for the trip."},
          "groups": {"type": "List[UserGroupRecord]", "description": "A list of user group records for the trip."}
        }
      },
      "response": {
        "200": {
          "description": "The created trip.",
          "body": "TripMetadata"
        }
      }
    },
    "GetTripById": {
      "description": "Gets a trip by its ARN.",
      "request": {
        "method": "GET",
        "path": "/trips/{tripArn}"
      },
      "response": {
        "200": {
          "description": "The trip metadata and user status.",
          "body": {
            "trip": {"type": "TripMetadata", "description": "The trip metadata."},
            "status": {"type": "Object", "description": "The user's status for the trip."}
          }
        }
      }
    },
    "GetUserTrips": {
      "description": "Gets all trips for the current user.",
      "request": {
        "method": "GET",
        "path": "/trips",
        "queryParams": {
          "status": {"type": "String", "description": "Filter trips by status (completed, uncompleted, all)."}
        }
      },
      "response": {
        "200": {
          "description": "A list of trips for the user.",
          "body": {
            "trips": {"type": "List[TripMetadata]", "description": "A list of trip metadata objects, each with additional user-specific fields."}
          }
        }
      }
    },
    "ListTripUsers": {
      "description": "Gets all registered users for a trip.",
      "request": {
        "method": "GET",
        "path": "/trips/{tripArn}/users"
      },
      "response": {
        "200": {
          "description": "A list of users for the trip.",
          "body": {
            "users": {"type": "List[UserTrip]", "description": "A list of user trip objects."}
          }
        }
      }
    },
    "UpdateTripMetadata": {
      "description": "Updates the metadata for a trip.",
      "request": {
        "method": "PUT",
        "path": "/trips/{tripArn}",
        "body": {
          "startTime": {"type": "Option[Long]", "description": "The updated start time."},
          "notes": {"type": "Option[String]", "description": "The updated notes."},
          "locations": {"type": "Option[List[String]]", "description": "The updated list of location names in the desired order."}
        }
      },
      "response": {
        "200": {
          "description": "The updated trip metadata.",
          "body": "TripMetadata"
        }
      }
    },
    "StartTrip": {
      "description": "Starts a trip.",
      "request": {
        "method": "POST",
        "path": "/trips/{tripArn}/start"
      },
      "response": {
        "200": {
          "description": "The updated trip metadata.",
          "body": "TripMetadata"
        }
      }
    },
    "ArriveLocation": {
      "description": "Marks a location as arrived for a trip.",
      "request": {
        "method": "POST",
        "path": "/trips/{tripArn}/locations/{locationName}/arrival",
        "body": {
          "arrived": {"type": "Boolean", "description": "Must be true."}
        }
      },
      "response": {
        "200": {
          "description": "The updated trip metadata.",
          "body": "TripMetadata"
        }
      }
    },
    "LeaveTrip": {
      "description": "Allows a user to leave a trip. If the last registered user leaves, the trip is cancelled.",
      "request": {
        "method": "POST",
        "path": "/trips/{tripArn}/leave"
      },
      "response": {
        "200": {
          "description": "A confirmation message."
        }
      }
    },
    "JoinUserGroup": {
      "description": "Allows a user to join a user group.",
      "request": {
        "method": "POST",
        "path": "/user-groups/{groupArn}/join"
      },
      "response": {
        "200": {
          "description": "The updated user group.",
          "body": "UserGroupRecord"
        }
      }
    },
    "AcceptInvitation": {
      "description": "Accepts an invitation to join a user group.",
      "request": {
        "method": "POST",
        "path": "/user-groups/{groupArn}/accept"
      },
      "response": {
        "200": {
          "description": "The updated user group.",
          "body": "UserGroupRecord"
        }
      }
    },
    "UpdateUserGroup": {
      "description": "Updates a user group.",
      "request": {
        "method": "PUT",
        "path": "/user-groups/{groupArn}",
        "body": {
          "groupName": {"type": "Option[String]", "description": "The updated group name."},
          "start": {"type": "Option[String]", "description": "The updated start location."},
          "destination": {"type": "Option[String]", "description": "The updated destination."},
          "pickupTime": {"type": "Option[Long]", "description": "The updated pickup time."},
          "users": {"type": "Option[List[GroupUser]]", "description": "The updated list of users in the group."},
          "numAnonymousUsers": {"type": "Option[Int]", "description": "The updated number of anonymous users in the group."}
        }
      },
      "response": {
        "200": {
          "description": "The updated user group.",
          "body": "UserGroupRecord"
        }
      }
    },
    "CreateUserGroup": {
      "description": "Creates a new user group.",
      "request": {
        "method": "POST",
        "path": "/user-groups",
        "body": {
          "tripArn": {"type": "String", "description": "The ARN of the trip this group belongs to."},
          "groupName": {"type": "String", "description": "The name of the group."},
          "start": {"type": "String", "description": "The start location of the group."},
          "destination": {"type": "String", "description": "The destination of the group."},
          "pickupTime": {"type": "Long", "description": "The pickup time for the group."},
          "numAnonymousUsers": {"type": "Int", "description": "The number of anonymous users in the group."},
          "users": {"type": "List[GroupUser]", "description": "The users in the group."}
        }
      },
      "response": {
        "200": {
          "description": "The created user group.",
          "body": "UserGroupRecord"
        }
      }
    },
    "BecomeDriver": {
      "description": "Allows a user to become the driver for a trip.",
      "request": {
        "method": "POST",
        "path": "/trips/{tripArn}/driver"
      },
      "response": {
        "200": {
          "description": "The updated trip metadata.",
          "body": "TripMetadata"
        }
      }
    }
  }
}