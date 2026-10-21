using System.Windows;

namespace Gallery;

public partial class App : Application
{
    public App()
    {
        // Show problems instead of closing without a word.
        DispatcherUnhandledException += (_, e) =>
        {
            MessageBox.Show(e.Exception.InnerException?.Message ?? e.Exception.Message, "Gallery", MessageBoxButton.OK, MessageBoxImage.Warning);
            e.Handled = true;
        };
    }
}
