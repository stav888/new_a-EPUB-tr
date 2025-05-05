// Google Apps Script to handle logging from the EPUB Translator app
// This script should be deployed as a web app with "Execute as: Me" and "Who has access: Anyone"

// The doPost function is called when the app sends a POST request
function doPost(e) {
  try {
    // Parse the POST data
    var data = e.parameter;
    
    // Get the action to perform
    var action = data.action;
    
    // Get the sheet ID
    var sheetId = data.sheetId;
    
    // Open the spreadsheet
    var spreadsheet = SpreadsheetApp.openById(sheetId);
    
    // Handle different actions
    if (action === "logTranslation") {
      return logTranslation(spreadsheet, data);
    } else if (action === "logJavaScriptEvent") {
      return logJavaScriptEvent(spreadsheet, data);
    } else {
      return ContentService.createTextOutput(JSON.stringify({
        "status": "error",
        "message": "Unknown action: " + action
      }));
    }
  } catch (error) {
    return ContentService.createTextOutput(JSON.stringify({
      "status": "error",
      "message": error.toString()
    }));
  }
}

// Log a translation attempt
function logTranslation(spreadsheet, data) {
  // Get or create the "Translations" sheet
  var sheet = spreadsheet.getSheetByName("Translations");
  if (!sheet) {
    sheet = spreadsheet.insertSheet("Translations");
    
    // Add headers
    sheet.appendRow([
      "Timestamp",
      "Session ID",
      "Device Info",
      "Source Text",
      "Source Language",
      "Target Language",
      "Is Offline",
      "Is Successful",
      "Translated Text",
      "Error Message"
    ]);
  }
  
  // Append the data
  sheet.appendRow([
    data.timestamp,
    data.sessionId,
    data.deviceInfo,
    data.sourceText,
    data.sourceLanguage,
    data.targetLanguage,
    data.isOffline,
    data.isSuccessful,
    data.translatedText,
    data.errorMessage
  ]);
  
  // Return success
  return ContentService.createTextOutput(JSON.stringify({
    "status": "success",
    "message": "Translation logged successfully"
  }));
}

// Log a JavaScript event
function logJavaScriptEvent(spreadsheet, data) {
  // Get or create the "JavaScript Events" sheet
  var sheet = spreadsheet.getSheetByName("JavaScript Events");
  if (!sheet) {
    sheet = spreadsheet.insertSheet("JavaScript Events");
    
    // Add headers
    sheet.appendRow([
      "Timestamp",
      "Session ID",
      "Device Info",
      "Event Type",
      "Element ID",
      "Element Text",
      "Additional Info"
    ]);
  }
  
  // Append the data
  sheet.appendRow([
    data.timestamp,
    data.sessionId,
    data.deviceInfo,
    data.eventType,
    data.elementId,
    data.elementText,
    data.additionalInfo
  ]);
  
  // Return success
  return ContentService.createTextOutput(JSON.stringify({
    "status": "success",
    "message": "JavaScript event logged successfully"
  }));
}

// The doGet function is called when someone visits the web app URL
function doGet() {
  return ContentService.createTextOutput("EPUB Translator Logging Service is running.");
}
