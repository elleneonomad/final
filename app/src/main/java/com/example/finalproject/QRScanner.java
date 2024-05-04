package com.example.finalproject;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.CompoundBarcodeView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
// TODO: Add student history collection to the firebase collection to USERS that are role "student"
public class QRScanner extends AppCompatActivity {
    private CompoundBarcodeView barcodeView;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    // Flag to track whether the scanning process is ongoing
    private boolean scanningInProgress = false;
    // List to track scanned courses for each day of the week
    private List<String>[] scannedCourses;
    String currentUserID;
    String courseName;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qrscanner);

        // Initialize the barcode scanner view
        barcodeView = findViewById(R.id.barcode_scanner);
        // current userID
        currentUserID = FirebaseAuth.getInstance().getUid();
        // Initialize the list to track scanned courses for each day of the week
        scannedCourses = new List[7];
        for (int i = 0; i < 7; i++) {
            scannedCourses[i] = new ArrayList<>();
        }
        // Check if the camera permission is granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted, request the permission
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            // Permission is already granted, start scanning
            startScanning();
        }
    }

    // Method to start scanning when permission is granted
    private void startScanning() {
        // Check if scanning is already in progress
        if (scanningInProgress) {
            return;
        }
        // Set barcode callback to handle scanned results
        barcodeView.decodeSingle(new BarcodeCallback() {
            // Inside the barcodeResult method of your QRScanner activity
            @Override
            public void barcodeResult(BarcodeResult result) {
                if (result.getText() != null) {
                    String qrCodeID = result.getText();
                    // Set scanningInProgress flag to false to prevent further scans
                    scanningInProgress = false;
                    FirebaseFirestore.getInstance().collection("courses")
                            .whereEqualTo("qrCodeID", qrCodeID)
                            .get()
                            .addOnSuccessListener(queryDocumentSnapshots -> {
                                for (QueryDocumentSnapshot document : queryDocumentSnapshots) {
                                    String courseId = document.getId();
                                    courseName = document.getString("name");
                                    checkCourseSchedule(courseId, courseName, () -> {
                                        // This callback will be executed once the course schedule has been checked
                                        // You can perform any further actions here
                                    });
                                }
                            })
                            .addOnFailureListener(e -> {
                                // Handle failure
                                Toast.makeText(QRScanner.this, "Failed to retrieve course information", Toast.LENGTH_SHORT).show();
                            });
                }
            }// trem nis correct hz

            // Method to check course schedule and mark attendance
            private void checkCourseSchedule(String courseId, String courseName, Runnable callback) {
                // Get the current date and time
                Calendar currentTime = Calendar.getInstance();

                // Get the current day of the week (Sunday = 1, Monday = 2, ..., Saturday = 7)
                int currentDayOfWeek = currentTime.get(Calendar.DAY_OF_WEEK);

                // Check if the course has already been scanned for the current day
                if (scannedCourses[currentDayOfWeek - 1].contains(courseId)) {
                    // Course has already been scanned for the current day
                    Toast.makeText(QRScanner.this, "Course has already been scanned for today", Toast.LENGTH_SHORT).show();
                    // Invoke the callback function to handle further actions
                    callback.run();
                    return;
                }

                // Query Firestore to retrieve the course details
                FirebaseFirestore.getInstance().collection("courses")
                        .document(courseId)
                        .get()
                        .addOnSuccessListener(documentSnapshot -> {
                            if (documentSnapshot.exists()) {
                                // Retrieve course information
                                String startTime = documentSnapshot.getString("startTime");
                                String endTime = documentSnapshot.getString("endTime");
                                // Get the list of active days for the course from Firestore
                                List<Boolean> activeDays = (List<Boolean>) documentSnapshot.get("daysOfWeek");
                                // Check if the current day is one of the active days for the course
                                if (activeDays != null && currentDayOfWeek <= activeDays.size() && activeDays.get(currentDayOfWeek - 1)) {
                                    // Parse start and end times
                                    Calendar sessionStartDateTime = Calendar.getInstance();
                                    Calendar sessionEndDateTime = Calendar.getInstance();
                                    sessionStartDateTime.set(Calendar.HOUR_OF_DAY, getHourOfDay(startTime));
                                    sessionStartDateTime.set(Calendar.MINUTE, getMinuteOfHour(startTime));
                                    sessionEndDateTime.set(Calendar.HOUR_OF_DAY, getHourOfDay(endTime));
                                    sessionEndDateTime.set(Calendar.MINUTE, getMinuteOfHour(endTime));

                                    // Compare current time with session start and end time
                                    if (currentTime.after(sessionStartDateTime) && currentTime.before(sessionEndDateTime)) {
                                        // If the current time falls within the session start and end time, mark attendance
                                        markAttendance(courseId, currentTime.getTime(), sessionStartDateTime, sessionEndDateTime);
                                    } else {
                                        // Mark absent if time is > the endTime of the course
                                        markAbsent(courseId,currentTime.getTime());
                                    }
                                    // Add the scanned course to the list of scanned courses for the current day
                                    scannedCourses[currentDayOfWeek - 1].add(courseId);
                                } else {
                                    // Course is not active on the current day
                                    Toast.makeText(QRScanner.this, "Course is not active today", Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                // Course document does not exist
                                Toast.makeText(QRScanner.this, "Course not found", Toast.LENGTH_SHORT).show();
                            }
                            // Invoke the callback function once the schedule has been checked
                            callback.run();
                        })
                        .addOnFailureListener(e -> {
                            // Handle failure
                            Toast.makeText(QRScanner.this, "Failed to retrieve course details", Toast.LENGTH_SHORT).show();
                            // Invoke the callback function in case of failure as well
                            callback.run();
                        });
            }


            // Helper method to extract hour from time string
            private int getHourOfDay(String time) {
                String[] parts = time.split(":");
                return Integer.parseInt(parts[0]);
            }

            // Helper method to extract minute from time string
            private int getMinuteOfHour(String time) {
                String[] parts = time.split(":");
                return Integer.parseInt(parts[1]);
            }
            // Method to mark absent
            private void markAbsent(String courseId, Date sessionDateTime){
                String status = "Absent";
                Calendar currentTime = Calendar.getInstance();
                Toast.makeText(QRScanner.this, "Status: " + status, Toast.LENGTH_SHORT).show();
                FirebaseFirestore.getInstance().collection("courses")
                        .document(courseId)
                        .collection("enrolledStudents")
                        .whereEqualTo("enrolled", true) // Filter only enrolled students
                        .get()
                        .addOnSuccessListener(queryDocumentSnapshots -> {
                            // Iterate through each enrolled student
                            for (QueryDocumentSnapshot studentDoc : queryDocumentSnapshots) {
                                // Retrieve student ID and mark attendance for the session
                                String studentId = studentDoc.getString("userID");
                                // You can update the Firestore database to mark attendance for the session
                                // For example, you can add a new document in the attendance collection for the session
                                // with the student ID and session date/time
                                FirebaseFirestore.getInstance().collection("courses")
                                        .document(courseId)
                                        .collection("attendance")
                                        .document(studentId + "_" + sessionDateTime.getTime())
                                        .set(new Attendance(studentId, sessionDateTime, status))
                                        .addOnSuccessListener(aVoid -> {
                                            // Handle success
                                            Toast.makeText(QRScanner.this, "Attendance marked for " + studentId, Toast.LENGTH_SHORT).show();
                                        })
                                        .addOnFailureListener(e -> {
                                            // Handle failure
                                            Toast.makeText(QRScanner.this, "Failed to mark attendance for " + studentId, Toast.LENGTH_SHORT).show();
                                        });
                            }
                        })
                        .addOnFailureListener(e -> {
                            // Handle failure
                            Toast.makeText(QRScanner.this, "Failed to retrieve enrolled students", Toast.LENGTH_SHORT).show();
                        });
                addAttendanceToUserHistory(currentUserID,currentTime.getTime(),courseId,courseName,status);

            }

            // Method to mark attendance for enrolled students
            private void markAttendance(String courseId, Date sessionDateTime, Calendar startTime, Calendar endTime) {
                Calendar currentTime = Calendar.getInstance();
                String status = currentTime.after(startTime) && currentTime.before(endTime) ? "present" : "absent";
                Toast.makeText(QRScanner.this, "Status: " + status, Toast.LENGTH_SHORT).show();
                // Query Firestore to get enrolled students for the course
                FirebaseFirestore.getInstance().collection("courses")
                        .document(courseId)
                        .collection("enrolledStudents")
                        .whereEqualTo("enrolled", true) // Filter only enrolled students
                        .get()
                        .addOnSuccessListener(queryDocumentSnapshots -> {
                            // Iterate through each enrolled student
                            for (QueryDocumentSnapshot studentDoc : queryDocumentSnapshots) {
                                // Retrieve student ID and mark attendance for the session
                                String studentId = studentDoc.getString("userID");
                                // You can update the Firestore database to mark attendance for the session
                                // For example, you can add a new document in the attendance collection for the session
                                // with the student ID and session date/time
                                FirebaseFirestore.getInstance().collection("courses")
                                        .document(courseId)
                                        .collection("attendance")
                                        .document(studentId + "_" + sessionDateTime.getTime())
                                        .set(new Attendance(studentId, sessionDateTime, status))
                                        .addOnSuccessListener(aVoid -> {
                                            // Handle success
                                            Toast.makeText(QRScanner.this, "Attendance marked for " + studentId, Toast.LENGTH_SHORT).show();
                                        })
                                        .addOnFailureListener(e -> {
                                            // Handle failure
                                            Toast.makeText(QRScanner.this, "Failed to mark attendance for " + studentId, Toast.LENGTH_SHORT).show();
                                        });
                            }
                        })
                        .addOnFailureListener(e -> {
                            // Handle failure
                            Toast.makeText(QRScanner.this, "Failed to retrieve enrolled students", Toast.LENGTH_SHORT).show();
                        });
                addAttendanceToUserHistory(currentUserID,currentTime.getTime(),courseId,courseName,status);
            }
            // Inside your QRScanner class

            // Method to add attendance history to the user's document in Firestore
            private void addAttendanceToUserHistory(String studentId, Date sessionDateTime, String courseId, String courseName, String status) {
                // Get Firestore instance
                FirebaseFirestore db = FirebaseFirestore.getInstance();

                // Query Firestore to find the user document with the matching userID
                db.collection("users").whereEqualTo("userID", studentId).get()
                        .addOnSuccessListener(queryDocumentSnapshots -> {
                            // Check if any documents were found
                            if (!queryDocumentSnapshots.isEmpty()) {
                                // Assuming there is only one document for each user, but you might need additional logic
                                String userDocumentId = queryDocumentSnapshots.getDocuments().get(0).getId();

                                // Create a reference to the attendance history subcollection for the user
                                // and set a new document with the session date/time as the document ID
                                db.collection("users").document(userDocumentId)
                                        .collection("attendance_history").document(sessionDateTime.toString())
                                        .set(new AttendanceHistory(courseId,courseName, sessionDateTime, status))
                                        .addOnSuccessListener(aVoid -> {
                                            // Handle success
                                            Log.d("QRScanner", "Attendance history added to user: " + studentId);
                                        })
                                        .addOnFailureListener(e -> {
                                            // Handle failure
                                            Log.e("QRScanner", "Failed to add attendance history to user: " + studentId, e);
                                        });
                            } else {
                                // No user document found with the given userID
                                Log.e("QRScanner", "No user document found with userID: " + studentId);
                            }
                        })
                        .addOnFailureListener(e -> {
                            // Handle failure
                            Log.e("QRScanner", "Failed to query users collection", e);
                        });
            }


        });

    }

    // Handle permission request result
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            // Check if the permission request is granted
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission is granted, start scanning
                startScanning();
            } else {
                // Permission is denied, display a message or handle it accordingly
                Toast.makeText(this, "Camera permission is required to scan QR codes", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Start camera preview when the activity resumes
        barcodeView.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Pause camera preview when the activity is paused
        barcodeView.pause();
    }
}


