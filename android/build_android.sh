#!/bin/bash
# DYLANDOS IPTV ULTIMATE - Android Build Script (Linux/macOS)
# Version: 1.5.0-ULTRA

echo "================================================"
echo "DYLANDOS IPTV ULTIMATE - Android Build"
echo "Version: 1.5.0-ULTRA"
echo "================================================"
echo ""

# Check if Gradle wrapper exists
if [ ! -f "./gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "ERROR: Gradle wrapper jar is missing!"
    echo ""
    echo "Please download gradle-wrapper.jar:"
    echo "1. Run: wget https://services.gradle.org/distributions/gradle-8.2-bin.zip"
    echo "2. Run: unzip gradle-8.2-bin.zip"
    echo "3. Run: cp gradle-8.2/lib/plugins/gradle-wrapper.jar ./gradle/wrapper/"
    echo ""
    echo "Or install gradle and run: gradle wrapper"
    exit 1
fi

# Make gradlew executable
chmod +x ./gradlew

function show_menu() {
    echo ""
    echo "Select build variant:"
    echo "1. Firestick Debug (ARMv7 - for testing)"
    echo "2. Firestick Release (ARMv7 - for Fire TV Stick 4K)"
    echo "3. Premium Debug (ARM64 - for testing)"
    echo "4. Premium Release (ARM64 - for high-end devices)"
    echo "5. Build ALL variants"
    echo "6. Clean build"
    echo "7. Exit"
    echo ""
    read -p "Enter choice (1-7): " choice

    case $choice in
        1) firestick_debug ;;
        2) firestick_release ;;
        3) premium_debug ;;
        4) premium_release ;;
        5) build_all ;;
        6) clean_build ;;
        7) exit 0 ;;
        *) echo "Invalid choice"; show_menu ;;
    esac
}

function firestick_debug() {
    echo ""
    echo "Building Firestick DEBUG (ARMv7)..."
    echo ""
    ./gradlew assembleFirestickDebug
    if [ $? -eq 0 ]; then
        echo ""
        echo "✓ Build successful!"
        echo "APK location: app/build/outputs/apk/firestick/debug/"
        open app/build/outputs/apk/firestick/debug/ 2>/dev/null || xdg-open app/build/outputs/apk/firestick/debug/ 2>/dev/null
    else
        echo "✗ Build failed! Check error messages above."
    fi
    read -p "Press Enter to continue..."
    show_menu
}

function firestick_release() {
    echo ""
    echo "Building Firestick RELEASE (ARMv7)..."
    echo ""
    ./gradlew assembleFirestickRelease
    if [ $? -eq 0 ]; then
        echo ""
        echo "✓ Build successful!"
        echo "APK location: app/build/outputs/apk/firestick/release/"
        open app/build/outputs/apk/firestick/release/ 2>/dev/null || xdg-open app/build/outputs/apk/firestick/release/ 2>/dev/null
    else
        echo "✗ Build failed! Check error messages above."
    fi
    read -p "Press Enter to continue..."
    show_menu
}

function premium_debug() {
    echo ""
    echo "Building Premium DEBUG (ARM64)..."
    echo ""
    ./gradlew assemblePremiumDebug
    if [ $? -eq 0 ]; then
        echo ""
        echo "✓ Build successful!"
        echo "APK location: app/build/outputs/apk/premium/debug/"
        open app/build/outputs/apk/premium/debug/ 2>/dev/null || xdg-open app/build/outputs/apk/premium/debug/ 2>/dev/null
    else
        echo "✗ Build failed! Check error messages above."
    fi
    read -p "Press Enter to continue..."
    show_menu
}

function premium_release() {
    echo ""
    echo "Building Premium RELEASE (ARM64)..."
    echo ""
    ./gradlew assemblePremiumRelease
    if [ $? -eq 0 ]; then
        echo ""
        echo "✓ Build successful!"
        echo "APK location: app/build/outputs/apk/premium/release/"
        open app/build/outputs/apk/premium/release/ 2>/dev/null || xdg-open app/build/outputs/apk/premium/release/ 2>/dev/null
    else
        echo "✗ Build failed! Check error messages above."
    fi
    read -p "Press Enter to continue..."
    show_menu
}

function build_all() {
    echo ""
    echo "Building ALL variants..."
    echo ""
    ./gradlew assembleFirestickDebug assembleFirestickRelease assemblePremiumDebug assemblePremiumRelease
    if [ $? -eq 0 ]; then
        echo ""
        echo "✓ All builds successful!"
        echo ""
        echo "APK locations:"
        echo "- Firestick Debug:   app/build/outputs/apk/firestick/debug/"
        echo "- Firestick Release: app/build/outputs/apk/firestick/release/"
        echo "- Premium Debug:     app/build/outputs/apk/premium/debug/"
        echo "- Premium Release:   app/build/outputs/apk/premium/release/"
        echo ""
        open app/build/outputs/apk 2>/dev/null || xdg-open app/build/outputs/apk 2>/dev/null
    else
        echo "✗ Build failed! Check error messages above."
    fi
    read -p "Press Enter to continue..."
    show_menu
}

function clean_build() {
    echo ""
    echo "Cleaning build artifacts..."
    echo ""
    ./gradlew clean
    if [ $? -eq 0 ]; then
        echo "✓ Clean successful!"
    else
        echo "✗ Clean failed!"
    fi
    read -p "Press Enter to continue..."
    show_menu
}

# Start menu
show_menu
