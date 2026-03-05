{
  "dataModels": {
    "UserGroups": {
      "description": "This table stores information about user groups for trips.",
      "fields": [
        {
          "field": "groupArn",
          "type": "String",
          "description": "The Amazon Resource Name (ARN) for the user group. This is the primary key."
        },
        {
          "field": "tripArn",
          "type": "String",
          "description": "The ARN of the trip this group is associated with. There is a Global Secondary Index (GSI) on this field (`gsiTripArn`)."
        },
        {
          "field": "groupName",
          "type": "String",
          "description": "The name of the user group."
        },
        {
          "field": "start",
          "type": "String",
          "description": "The starting location for the group's trip."
        },
        {
          "field": "destination",
          "type": "String",
          "description": "The destination for the group's trip."
        },
        {
          "field": "pickupTime",
          "type": "Long",
          "description": "The scheduled pickup time for the group, stored as a Unix timestamp."
        },
        {
          "field": "version",
          "type": "Int",
          "description": "A version number for the record, used for optimistic locking to prevent concurrent modification issues."
        },
        {
          "field": "users",
          "type": "List of GroupUser",
          "description": "A list of users within the group."
        }
      ],
      "nestedModels": {
        "GroupUser": {
          "description": "This nested object contains details about each user in a group.",
          "fields": [
            {
              "field": "userId",
              "type": "String",
              "description": "The unique identifier for the user."
            },
            {
              "field": "name",
              "type": "String",
              "description": "The user's name."
            },
            {
              "field": "imageUrl",
              "type": "Option[String]",
              "description": "An optional URL for the user's profile image."
            },
            {
              "field": "accept",
              "type": "Boolean",
              "description": "A flag indicating whether the user has accepted the trip invitation."
            }
          ]
        }
      }
    },
    "TripMetadata": {
      "description": "This table holds the central information about a trip, aggregating data from various sources.",
      "fields": [
        {
          "field": "tripArn",
          "type": "String",
          "description": "A 6-digit alphanumeric string that uniquely identifies the trip, serving as the primary key."
        },
        {
          "field": "locations",
          "type": "List of Location",
          "description": "An ordered list of locations (stops) for the trip."
        },
        {
          "field": "startTime",
          "type": "Long",
          "description": "The start date of the trip, likely stored as a Unix timestamp."
        },
        {
          "field": "completionTime",
          "type": "Option[Long]",
          "description": "An optional Unix timestamp for when the trip was completed."
        },
        {
          "field": "status",
          "type": "String",
          "description": "The current status of the trip (e.g., 'Upcoming', 'InProgress', 'Completed')."
        },
        {
          "field": "driver",
          "type": "Option[String]",
          "description": "An optional user ID of the person driving."
        },
        {
          "field": "driverName",
          "type": "Option[String]",
          "description": "An optional name of the person driving."
        },
        {
          "field": "driverPhotoUrl",
          "type": "Option[String]",
          "description": "An optional URL for the driver's photo."
        },
        {
          "field": "driverConfirmed",
          "type": "Option[Boolean]",
          "description": "An optional flag indicating if the driver has confirmed their participation."
        },
        {
          "field": "car",
          "type": "Option[Car]",
          "description": "An optional object containing details about the vehicle."
        },
        {
          "field": "usergroups",
          "type": "Option[List of UserGroup]",
          "description": "A summary of the user groups participating in the trip. This should only be populated for UnCompleted trip"
        },
        {
          "field": "users",
          "type": "Option[List of TripUser]",
          "description": "A list of all users involved in the trip. This should only be populated for Completed trip"
        },
        {
          "field": "notes",
          "type": "Option[String]",
          "description": "Optional notes or additional information about the trip."
        },
        {
          "field": "version",
          "type": "Int",
          "description": "A version number for optimistic locking."
        }
      ],
      "nestedModels": {
        "Location": {
          "fields": [
            {
              "field": "locationName",
              "type": "String",
              "description": "The name of the location."
            },
            {
              "field": "plannedTime",
              "type": "Long",
              "description": "Planned/tentative arrival time at this location (Unix ms). Stored only in TripMetadata; set and updated solely during transactional writes (trip creation, user group add/update/remove). No cross-table lookup or sync outside those transactions."
            },
            {
              "field": "pickupGroups",
              "type": "List of String",
              "description": "A list of `groupArn`s that will be picked up at this location."
            },
            {
              "field": "dropOffGroups",
              "type": "List of String",
              "description": "A list of `groupArn`s that will be dropped off at this location."
            },
            {
              "field": "arrived",
              "type": "Boolean",
              "description": "A flag indicating if the vehicle has arrived at this location."
            },
            {
              "field": "arrivedTime",
              "type": "Option[Long]",
              "description": "An optional Unix timestamp indicating when the vehicle arrived at this location."
            }
          ]
        },
        "Car": {
          "fields": [
            {
              "field": "plateNumber",
              "type": "Option[String]",
              "description": "The vehicle's license plate number."
            },
            {
              "field": "color",
              "type": "Option[String]",
              "description": "The color of the vehicle."
            },
            {
              "field": "model",
              "type": "Option[String]",
              "description": "The model of the vehicle."
            }
          ]
        },
        "UserGroup": {
          "description": "This is a summary object within TripMetadata.",
          "fields": [
            {
              "field": "groupId",
              "type": "String",
              "description": "The group's ARN."
            },
            {
              "field": "groupName",
              "type": "String",
              "description": "The name of the group."
            },
            {
              "field": "groupSize",
              "type": "Int",
              "description": "The number of users in the group."
            },
            {
              "field": "imageUrl",
              "type": "Option[String]",
              "description": "An optional image URL for the group."
            }
          ]
        },
        "TripUser": {
          "fields": [
            {
              "field": "userId",
              "type": "Option[String]",
              "description": "The unique identifier for the user."
            },
            {
              "field": "name",
              "type": "String",
              "description": "The user's name."
            },
            {
              "field": "imageUrl",
              "type": "Option[String]",
              "description": "An optional URL for the user's profile image."
            }
          ]
        }
      }
    },
    "UserTrips": {
      "description": "This table tracks the relationship between a user and a specific trip, including their status.",
      "fields": [
        {
          "field": "arn",
          "type": "String",
          "description": "The primary key, constructed from `tripArn:userId`."
        },
        {
          "field": "tripArn",
          "type": "String",
          "description": "The ARN of the trip. This is used for a GSI (`gsiTripArn`) to allow efficient queries for all users in a trip."
        },
        {
          "field": "userStatusKey",
          "type": "String",
          "description": "A composite key of `userId-tripStatus`. This is used for a GSI (`gsiUserTripStatusDateTime`) to allow efficient queries for a user's trips by status. tripStatus here means completed/uncompleted, not the detailed status"
        },
        {
          "field": "tripStatus",
          "type": "String",
          "description": "The user's status for this trip (e.g., 'Invitation', 'Upcoming', 'InProgress', 'Completed)."
        },
        {
          "field": "start",
          "type": "String",
          "description": "The user's starting location for the trip."
        },
        {
          "field": "destination",
          "type": "String",
          "description": "The user's destination for the trip."
        },
        {
          "field": "departureDateTime",
          "type": "Long",
          "description": "The user's departure time, likely a Unix timestamp."
        },
        {
          "field": "isDriver",
          "type": "Boolean",
          "description": "A flag indicating if the user is the driver for this trip."
        },
        {
          "field": "driverConfirmed",
          "type": "Boolean",
          "description": "A flag indicating if the driver is confirmed."
        },
        {
          "field": "version",
          "type": "Int",
          "description": "A version number for the record."
        }
      ]
    }
  }
}